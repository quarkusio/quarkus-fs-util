package io.quarkus.fs.util.rozip;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

/**
 * A memory-efficient, immutable index of ZIP central directory entries.
 * <p>
 * This replaces the conventional {@code Map<String, ZipEntryInfo>} and
 * {@code Map<String, List<String>>} (directory children) with a flat,
 * sorted structure that eliminates per-entry object overhead from
 * {@link java.util.HashMap.Node}, {@link String}, and {@link ZipEntryInfo}
 * instances. Measured savings are approximately 60% compared to the
 * map-based representation (e.g. 9.9 MB vs 25.5 MB for 96K entries).
 *
 * <h2>Data layout</h2>
 *
 * All entry names are encoded as UTF-8 and concatenated into a single
 * {@code byte[]} ({@link #nameBytes}). A parallel {@code int[]}
 * ({@link #nameOffsets}) stores the start offset of each name;
 * {@code nameOffsets[i+1] - nameOffsets[i]} gives the length of name
 * {@code i}. The entries are sorted by unsigned byte order of their
 * UTF-8 names, which enables O(log n) lookup via binary search.
 * <p>
 * Entry metadata (compressed size, uncompressed size, CRC-32, local
 * header offset, last-modified time, compression method, directory flag)
 * is stored in parallel primitive arrays indexed by the same entry index,
 * keeping data dense and cache-friendly.
 *
 * <pre>
 * nameBytes:   [ c o m / A . c l a s s c o m / B . c l a s s ]
 * nameOffsets: [ 0,                    11,                    22 ]
 *               ^--- entry 0 ---^      ^--- entry 1 ---^
 *
 * compressedSizes:   [ 1234,  5678  ]   (parallel, same index)
 * uncompressedSizes: [ 2048,  8192  ]
 * crc32Values:       [ 0xAB, 0xCD   ]
 * ...
 * </pre>
 *
 * <h2>Directory tree</h2>
 *
 * Directory children are derived from the sorted entry names at query
 * time rather than stored in a separate map. Because entries sharing
 * a directory prefix are adjacent in sorted
 * order, listing the children of directory {@code "com/example"} is a
 * binary search for the prefix {@code "com/example/"} followed by a
 * forward scan that extracts unique immediate child names. Implicit
 * directories (not present as explicit entries in the ZIP but implied
 * by file paths) are detected by checking whether any entry name
 * starts with the directory prefix.
 *
 * <h2>Thread safety</h2>
 *
 * Instances are immutable after construction and safe for concurrent
 * use by multiple threads without synchronization.
 *
 * @see ZipCentralDirectory
 * @see ReadOnlyZipFileSystem
 */
final class CompactEntryTable {

    private static final byte[] EMPTY_BYTES = new byte[0];

    // Central directory entry header field offsets and sizes
    private static final int CD_HEADER_SIZE = 46;
    private static final int CD_OFF_COMPRESSION_METHOD = 10;
    private static final int CD_OFF_DOS_TIME = 12;
    private static final int CD_OFF_DOS_DATE = 14;
    private static final int CD_OFF_CRC32 = 16;
    private static final int CD_OFF_COMPRESSED_SIZE = 20;
    private static final int CD_OFF_UNCOMPRESSED_SIZE = 24;
    private static final int CD_OFF_NAME_LENGTH = 28;
    private static final int CD_OFF_EXTRA_LENGTH = 30;
    private static final int CD_OFF_COMMENT_LENGTH = 32;
    private static final int CD_OFF_LOCAL_HEADER_OFFSET = 42;

    private final byte[] nameBytes;
    private final int[] nameOffsets;
    private final long[] localHeaderOffsets;
    private final long[] compressedSizes;
    private final long[] uncompressedSizes;
    private final int[] crc32Values;
    private final long[] lastModifiedTimes;
    private final byte[] compressionMethods;
    private final BitSet directories;
    private final int entryCount;

    /**
     * Builds a compact entry table directly from raw central directory bytes.
     * Parses entry metadata and references names as (offset, length) pairs
     * into {@code cdBytes} throughout parsing and sorting, then copies the
     * sorted names into a single concatenated byte array.
     *
     * @param cdBytes the raw central directory bytes
     * @param cdSize the number of valid bytes in {@code cdBytes}
     * @param totalEntries the declared number of entries
     * @param cdOffset the absolute file offset of the central directory (for error messages)
     * @return a new compact entry table
     * @throws IOException if the central directory is malformed
     */
    static CompactEntryTable buildFromCentralDirectory(byte[] cdBytes, int cdSize,
            long totalEntries, long cdOffset) throws IOException {
        int count = (int) Math.min(totalEntries, Integer.MAX_VALUE);
        if (count == 0) {
            return empty();
        }

        int[] nameStarts = new int[count];
        int[] nameLens = new int[count];
        long[] tmpLocalHeaderOffsets = new long[count];
        long[] tmpCompressedSizes = new long[count];
        long[] tmpUncompressedSizes = new long[count];
        int[] tmpCrc32Values = new int[count];
        long[] tmpLastModifiedTimes = new long[count];
        byte[] tmpCompressionMethods = new byte[count];
        BitSet tmpDirectories = new BitSet(count);

        int actualCount = 0;
        int pos = 0;
        for (int i = 0; i < count && pos + CD_HEADER_SIZE <= cdSize; i++) {
            if (LittleEndian.readInt32(cdBytes, pos) != ZipCentralDirectory.CENTRAL_DIR_SIG) {
                throw new IOException(
                        "Invalid central directory entry signature at offset " + (cdOffset + pos));
            }

            int method = LittleEndian.readUint16(cdBytes, pos + CD_OFF_COMPRESSION_METHOD);
            int dosTime = LittleEndian.readUint16(cdBytes, pos + CD_OFF_DOS_TIME);
            int dosDate = LittleEndian.readUint16(cdBytes, pos + CD_OFF_DOS_DATE);
            long crc32 = LittleEndian.readUint32(cdBytes, pos + CD_OFF_CRC32);
            long compressedSize = LittleEndian.readUint32(cdBytes, pos + CD_OFF_COMPRESSED_SIZE);
            long uncompressedSize = LittleEndian.readUint32(cdBytes, pos + CD_OFF_UNCOMPRESSED_SIZE);
            int origNameLen = LittleEndian.readUint16(cdBytes, pos + CD_OFF_NAME_LENGTH);
            int extraLen = LittleEndian.readUint16(cdBytes, pos + CD_OFF_EXTRA_LENGTH);
            int commentLen = LittleEndian.readUint16(cdBytes, pos + CD_OFF_COMMENT_LENGTH);
            long localHeaderOffset = LittleEndian.readUint32(cdBytes, pos + CD_OFF_LOCAL_HEADER_OFFSET);

            if (pos + CD_HEADER_SIZE + origNameLen + extraLen + commentLen > cdSize) {
                throw new IOException("Truncated central directory entry at offset " + (cdOffset + pos));
            }

            int extraOffset = pos + CD_HEADER_SIZE + origNameLen;

            int nameStart = pos + CD_HEADER_SIZE;
            int nameLen = origNameLen;
            if (nameLen > 0 && cdBytes[nameStart] == '/') {
                nameStart++;
                nameLen--;
            }
            boolean isDirectory = nameLen > 0 && cdBytes[nameStart + nameLen - 1] == '/';
            if (isDirectory) {
                nameLen--;
            }

            nameStarts[actualCount] = nameStart;
            nameLens[actualCount] = nameLen;

            if (ZipCentralDirectory.hasZip64Overrides(compressedSize, uncompressedSize,
                    localHeaderOffset)) {
                long[] zip64 = ZipCentralDirectory.readZip64Extra(cdBytes, extraOffset, extraLen,
                        uncompressedSize, compressedSize, localHeaderOffset);
                uncompressedSize = zip64[0];
                compressedSize = zip64[1];
                localHeaderOffset = zip64[2];
            }

            long lastModified = ZipCentralDirectory.readTimestampFromExtra(cdBytes,
                    extraOffset, extraLen,
                    ZipCentralDirectory.dosToEpochMillis(dosDate, dosTime));

            tmpLocalHeaderOffsets[actualCount] = localHeaderOffset;
            tmpCompressedSizes[actualCount] = compressedSize;
            tmpUncompressedSizes[actualCount] = uncompressedSize;
            tmpCrc32Values[actualCount] = (int) crc32;
            tmpLastModifiedTimes[actualCount] = lastModified;
            tmpCompressionMethods[actualCount] = (byte) method;
            if (isDirectory) {
                tmpDirectories.set(actualCount);
            }

            actualCount++;
            pos += CD_HEADER_SIZE + origNameLen + extraLen + commentLen;
        }

        return sortAndBuildFromCd(actualCount, cdBytes, nameStarts, nameLens,
                tmpLocalHeaderOffsets, tmpCompressedSizes, tmpUncompressedSizes,
                tmpCrc32Values, tmpLastModifiedTimes, tmpCompressionMethods, tmpDirectories);
    }

    private static CompactEntryTable empty() {
        return new CompactEntryTable(EMPTY_BYTES, new int[] { 0 },
                new long[0], new long[0], new long[0], new int[0],
                new long[0], new byte[0], new BitSet(), 0);
    }

    /**
     * Sorts entries by name, deduplicates, and builds the final compact
     * arrays. Names are referenced as (offset, length) pairs into
     * {@code cdBytes} and copied into the concatenated name array only
     * once, in sorted order.
     */
    private static CompactEntryTable sortAndBuildFromCd(int count, byte[] cdBytes,
            int[] nameStarts, int[] nameLens,
            long[] tmpLocalHeaderOffsets, long[] tmpCompressedSizes,
            long[] tmpUncompressedSizes, int[] tmpCrc32Values,
            long[] tmpLastModifiedTimes, byte[] tmpCompressionMethods,
            BitSet tmpDirectories) {

        int[] sortOrder = new int[count];
        for (int i = 0; i < count; i++) {
            sortOrder[i] = i;
        }
        mergeSort(sortOrder, count, (a, b) -> compareBytesRange(
                cdBytes, nameStarts[a], nameLens[a],
                cdBytes, nameStarts[b], nameLens[b]));

        boolean[] skip = new boolean[count];
        int skipCount = 0;
        int totalNameBytes = 0;
        for (int i = 0; i < count; i++) {
            if (i < count - 1
                    && nameLens[sortOrder[i]] == nameLens[sortOrder[i + 1]]
                    && regionEquals(cdBytes, nameStarts[sortOrder[i]],
                            cdBytes, nameStarts[sortOrder[i + 1]],
                            nameLens[sortOrder[i]])) {
                int skipIdx = sortOrder[i] < sortOrder[i + 1] ? i : i + 1;
                if (!skip[skipIdx]) {
                    skip[skipIdx] = true;
                    skipCount++;
                }
            }
            if (!skip[i]) {
                totalNameBytes += nameLens[sortOrder[i]];
            }
        }

        int finalCount = count - skipCount;

        byte[] nameBytesArr = new byte[totalNameBytes];
        int[] nameOffsetsArr = new int[finalCount + 1];
        long[] localHeaderOffsetsArr = new long[finalCount];
        long[] compressedSizesArr = new long[finalCount];
        long[] uncompressedSizesArr = new long[finalCount];
        int[] crc32Arr = new int[finalCount];
        long[] lastModArr = new long[finalCount];
        byte[] methodsArr = new byte[finalCount];
        BitSet dirsSet = new BitSet(finalCount);

        int namePos = 0;
        int outIdx = 0;
        for (int i = 0; i < count; i++) {
            if (skip[i]) {
                continue;
            }
            int orig = sortOrder[i];
            int nStart = nameStarts[orig];
            int nLen = nameLens[orig];

            nameOffsetsArr[outIdx] = namePos;
            System.arraycopy(cdBytes, nStart, nameBytesArr, namePos, nLen);
            namePos += nLen;

            localHeaderOffsetsArr[outIdx] = tmpLocalHeaderOffsets[orig];
            compressedSizesArr[outIdx] = tmpCompressedSizes[orig];
            uncompressedSizesArr[outIdx] = tmpUncompressedSizes[orig];
            crc32Arr[outIdx] = tmpCrc32Values[orig];
            lastModArr[outIdx] = tmpLastModifiedTimes[orig];
            methodsArr[outIdx] = tmpCompressionMethods[orig];
            if (tmpDirectories.get(orig)) {
                dirsSet.set(outIdx);
            }
            outIdx++;
        }
        nameOffsetsArr[finalCount] = namePos;

        return new CompactEntryTable(nameBytesArr, nameOffsetsArr,
                localHeaderOffsetsArr, compressedSizesArr, uncompressedSizesArr,
                crc32Arr, lastModArr, methodsArr, dirsSet, finalCount);
    }

    /**
     * Constructs a table from pre-built parallel arrays. All arrays must
     * be consistently indexed: element {@code i} across all arrays
     * describes the same entry. Names must be sorted by unsigned UTF-8
     * byte order.
     */
    private CompactEntryTable(byte[] nameBytes, int[] nameOffsets,
            long[] localHeaderOffsets, long[] compressedSizes,
            long[] uncompressedSizes, int[] crc32Values,
            long[] lastModifiedTimes, byte[] compressionMethods,
            BitSet directories, int entryCount) {
        this.nameBytes = nameBytes;
        this.nameOffsets = nameOffsets;
        this.localHeaderOffsets = localHeaderOffsets;
        this.compressedSizes = compressedSizes;
        this.uncompressedSizes = uncompressedSizes;
        this.crc32Values = crc32Values;
        this.lastModifiedTimes = lastModifiedTimes;
        this.compressionMethods = compressionMethods;
        this.directories = directories;
        this.entryCount = entryCount;
    }

    /**
     * @return the number of entries in this table
     */
    int size() {
        return entryCount;
    }

    /**
     * Looks up an entry by name. A new {@link ZipEntryInfo} is constructed
     * on each call from the parallel arrays; the caller's {@code name}
     * string is reused as the record's name field.
     *
     * @param name the entry name (without leading {@code "/"})
     * @return the entry info, or {@code null} if not found
     */
    ZipEntryInfo getEntry(String name) {
        int index = isAscii(name) ? binarySearchAscii(name) : binarySearch(toUTF8(name));
        if (index < 0) {
            return null;
        }
        return entryAt(index, name);
    }

    /**
     * Checks whether the given name exists as an explicit entry or as an
     * implicit directory (i.e. at least one entry has {@code name + "/"} as
     * a prefix).
     *
     * @param name the entry or directory name
     * @return {@code true} if the name exists
     */
    boolean exists(String name) {
        if (isAscii(name)) {
            return binarySearchAscii(name) >= 0 || hasEntriesUnderAscii(name);
        }
        byte[] nameUtf8 = toUTF8(name);
        if (binarySearch(nameUtf8) >= 0) {
            return true;
        }
        return hasEntriesUnder(nameUtf8);
    }

    /**
     * Checks whether any entry exists whose name starts with
     * {@code name + "/"}. This detects both explicit and implicit
     * directories.
     *
     * @param name the directory name to check
     * @return {@code true} if at least one entry is under this directory
     */
    boolean hasEntriesUnder(String name) {
        return isAscii(name) ? hasEntriesUnderAscii(name) : hasEntriesUnder(toUTF8(name));
    }

    /**
     * Returns the immediate children of the given directory. Child names
     * are simple names (e.g. {@code "Foo.class"}, {@code "sub"}), not
     * full paths. Both files and subdirectories are included.
     * <p>
     * For the root directory, pass the empty string {@code ""}.
     *
     * @param name the directory name (empty string for root)
     * @return an unmodifiable list of child names, an empty list for an
     *         empty explicit directory, or {@code null} if the name is
     *         not a directory
     */
    List<String> getDirectoryChildren(String name) {
        return isAscii(name) ? getDirectoryChildrenAscii(name) : getDirectoryChildrenBytes(name);
    }

    private List<String> getDirectoryChildrenAscii(String name) {
        int prefixLen = name.isEmpty() ? 0 : name.length() + 1;
        int start = lowerBoundAscii(name);
        if (start >= entryCount || !nameStartsWithAscii(start, name)) {
            if (name.isEmpty()) {
                return List.of();
            }
            int idx = binarySearchAscii(name);
            if (idx >= 0 && directories.get(idx)) {
                return List.of();
            }
            return null;
        }
        int end = start + 1;
        while (end < entryCount && nameStartsWithAscii(end, name)) {
            end++;
        }
        return collectChildren(start, end, prefixLen);
    }

    private List<String> getDirectoryChildrenBytes(String name) {
        byte[] nameUtf8 = toUTF8(name);
        byte[] prefix = toPrefixBytes(nameUtf8);
        int start = lowerBound(prefix);
        if (start >= entryCount || !nameStartsWith(start, prefix)) {
            if (nameUtf8.length == 0) {
                return List.of();
            }
            int idx = binarySearch(nameUtf8);
            if (idx >= 0 && directories.get(idx)) {
                return List.of();
            }
            return null;
        }
        int end = start + 1;
        while (end < entryCount && nameStartsWith(end, prefix)) {
            end++;
        }
        return collectChildren(start, end, prefix.length);
    }

    private List<String> collectChildren(int start, int end, int prefixLen) {
        List<String> children = new ArrayList<>();
        int prevStart = -1;
        int prevLen = -1;
        for (int i = start; i < end; i++) {
            int childStart = nameOffsets[i] + prefixLen;
            int childLen = immediateChildLen(childStart, nameOffsets[i + 1]);
            if (childLen != prevLen
                    || !regionEquals(nameBytes, childStart, nameBytes, prevStart, childLen)) {
                children.add(new String(nameBytes, childStart, childLen, StandardCharsets.UTF_8));
                prevStart = childStart;
                prevLen = childLen;
            }
        }
        return Collections.unmodifiableList(children);
    }

    /**
     * Constructs a {@link ZipEntryInfo} from the parallel arrays at the
     * given index.
     *
     * @param index position in the sorted arrays
     * @param name the caller's name string, reused as the record's name
     */
    private ZipEntryInfo entryAt(int index, String name) {
        return new ZipEntryInfo(
                name,
                compressedSizes[index],
                uncompressedSizes[index],
                compressionMethods[index] & 0xFF,
                Integer.toUnsignedLong(crc32Values[index]),
                localHeaderOffsets[index],
                directories.get(index),
                lastModifiedTimes[index]);
    }

    /**
     * Binary search for an exact name match.
     *
     * @param query the UTF-8 encoded name to search for
     * @return the index if found, or {@code -(insertion point) - 1} if not
     */
    private int binarySearch(byte[] query) {
        int lo = 0, hi = entryCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compareName(mid, query, 0, query.length);
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /**
     * Compares the name at {@code index} against a query byte range
     * using unsigned byte comparison. Returns negative if the stored
     * name is less than the query, zero if equal, positive if greater.
     */
    private int compareName(int index, byte[] query, int queryOffset, int queryLen) {
        int start = nameOffsets[index];
        int len = nameOffsets[index + 1] - start;
        return compareBytesRange(nameBytes, start, len, query, queryOffset, queryLen);
    }

    /**
     * Finds the index of the first entry whose name is greater than or
     * equal to {@code prefix} (lower bound). Returns {@link #entryCount}
     * if all names are strictly less than the prefix.
     */
    private int lowerBound(byte[] prefix) {
        if (prefix.length == 0) {
            return 0;
        }
        int lo = 0, hi = entryCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareNamePrefix(mid, prefix) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Compares the name at {@code index} against {@code prefix} for
     * lower-bound searching. Returns negative if the name is strictly
     * less than the prefix, zero or positive if the name starts with
     * (or is greater than) the prefix.
     */
    private int compareNamePrefix(int index, byte[] prefix) {
        int start = nameOffsets[index];
        int len = nameOffsets[index + 1] - start;
        int minLen = Math.min(len, prefix.length);
        for (int i = 0; i < minLen; i++) {
            int diff = (nameBytes[start + i] & 0xFF) - (prefix[i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return len < prefix.length ? -1 : 0;
    }

    /**
     * Returns {@code true} if the name at {@code index} starts with
     * the given prefix bytes.
     */
    private boolean nameStartsWith(int index, byte[] prefix) {
        if (prefix.length == 0) {
            return true;
        }
        int start = nameOffsets[index];
        int len = nameOffsets[index + 1] - start;
        return len >= prefix.length
                && regionEquals(nameBytes, start, prefix, 0, prefix.length);
    }

    /**
     * Returns the length of the immediate child name starting at
     * {@code childStart} — the number of bytes before the next
     * {@code '/'} or {@code nameEnd}, whichever comes first.
     */
    private int immediateChildLen(int childStart, int nameEnd) {
        for (int i = childStart; i < nameEnd; i++) {
            if (nameBytes[i] == '/') {
                return i - childStart;
            }
        }
        return nameEnd - childStart;
    }

    /**
     * Checks whether any entry name starts with {@code nameUtf8 + "/"}.
     *
     * @param nameUtf8 the pre-converted UTF-8 directory name bytes
     * @return {@code true} if at least one entry exists under this directory
     */
    private boolean hasEntriesUnder(byte[] nameUtf8) {
        byte[] prefix = toPrefixBytes(nameUtf8);
        int lb = lowerBound(prefix);
        return lb < entryCount && nameStartsWith(lb, prefix);
    }

    /**
     * Returns {@code true} if every character in the string is in the
     * ASCII range (0–127). When true, each {@code char} maps to exactly
     * one UTF-8 byte, so the {@code *Ascii} lookup methods can compare
     * characters directly against stored UTF-8 bytes without allocating
     * an intermediate {@code byte[]}.
     * <p>
     * ZIP entry names are overwhelmingly ASCII (Java package paths,
     * resource filenames, {@code META-INF/} entries), so this fast path
     * covers the vast majority of lookups in practice. Non-ASCII names
     * fall back to the {@code byte[]}-based methods via {@link #toUTF8}.
     */
    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) >= 128) {
                return false;
            }
        }
        return true;
    }

    /**
     * Allocation-free binary search for an exact name match against an
     * ASCII string. Compares {@link String#charAt(int)} values directly
     * against stored UTF-8 bytes. Only valid when the query is pure ASCII.
     *
     * @param query the ASCII entry name to search for
     * @return the index if found, or {@code -(insertion point) - 1} if not
     * @see #binarySearch(byte[])
     */
    private int binarySearchAscii(String query) {
        int lo = 0, hi = entryCount - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            int cmp = compareNameAscii(mid, query);
            if (cmp < 0) {
                lo = mid + 1;
            } else if (cmp > 0) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return -(lo + 1);
    }

    /**
     * Compares the name at {@code index} against an ASCII query string
     * using unsigned byte comparison, without allocating a {@code byte[]}.
     * Returns negative if the stored name is less than the query, zero
     * if equal, positive if greater.
     *
     * @see #compareName(int, byte[], int, int)
     */
    private int compareNameAscii(int index, String query) {
        int start = nameOffsets[index];
        int len = nameOffsets[index + 1] - start;
        int queryLen = query.length();
        int minLen = Math.min(len, queryLen);
        for (int i = 0; i < minLen; i++) {
            int diff = (nameBytes[start + i] & 0xFF) - query.charAt(i);
            if (diff != 0) {
                return diff;
            }
        }
        return len - queryLen;
    }

    /**
     * Finds the index of the first entry whose name is greater than or
     * equal to {@code dirName + "/"} without allocating a prefix
     * {@code byte[]}. Returns {@link #entryCount} if all names are
     * strictly less than the prefix, or {@code 0} for the root
     * directory (empty {@code dirName}).
     *
     * @see #lowerBound(byte[])
     */
    private int lowerBoundAscii(String dirName) {
        if (dirName.isEmpty()) {
            return 0;
        }
        int lo = 0, hi = entryCount;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (compareNamePrefixAscii(mid, dirName) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    /**
     * Compares the name at {@code index} against the virtual prefix
     * {@code dirName + "/"} for lower-bound searching, without
     * allocating a prefix {@code byte[]}. Returns negative if the
     * name is strictly less than the prefix, zero or positive if the
     * name starts with (or is greater than) the prefix.
     *
     * @see #compareNamePrefix(int, byte[])
     */
    private int compareNamePrefixAscii(int index, String dirName) {
        int start = nameOffsets[index];
        int len = nameOffsets[index + 1] - start;
        int prefixLen = dirName.length() + 1;
        int minLen = Math.min(len, prefixLen);
        for (int i = 0; i < minLen; i++) {
            int b = nameBytes[start + i] & 0xFF;
            int c = i < dirName.length() ? dirName.charAt(i) : '/';
            int diff = b - c;
            if (diff != 0) {
                return diff;
            }
        }
        return len < prefixLen ? -1 : 0;
    }

    /**
     * Returns {@code true} if the name at {@code index} starts with
     * {@code dirName + "/"}, comparing characters directly against
     * stored UTF-8 bytes without allocating a prefix {@code byte[]}.
     * Returns {@code true} for any entry when {@code dirName} is empty
     * (root directory).
     *
     * @see #nameStartsWith(int, byte[])
     */
    private boolean nameStartsWithAscii(int index, String dirName) {
        if (dirName.isEmpty()) {
            return true;
        }
        int start = nameOffsets[index];
        int len = nameOffsets[index + 1] - start;
        int prefixLen = dirName.length() + 1;
        if (len < prefixLen) {
            return false;
        }
        for (int i = 0; i < dirName.length(); i++) {
            if ((nameBytes[start + i] & 0xFF) != dirName.charAt(i)) {
                return false;
            }
        }
        return nameBytes[start + dirName.length()] == '/';
    }

    /**
     * Allocation-free variant of {@link #hasEntriesUnder(byte[])} that
     * checks whether any entry name starts with {@code name + "/"}
     * by comparing the ASCII string directly against stored UTF-8 bytes.
     *
     * @param name the directory name to check
     * @return {@code true} if at least one entry exists under this directory
     */
    private boolean hasEntriesUnderAscii(String name) {
        int lb = lowerBoundAscii(name);
        return lb < entryCount && nameStartsWithAscii(lb, name);
    }

    /**
     * Encodes a string to UTF-8 bytes. Single conversion point for all public methods.
     */
    private static byte[] toUTF8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Appends {@code '/'} to the given name bytes to form a directory
     * prefix suitable for {@link #lowerBound} and {@link #nameStartsWith}
     * searches. Returns {@link #EMPTY_BYTES} for the root directory
     * (empty input).
     */
    private static byte[] toPrefixBytes(byte[] nameUtf8) {
        if (nameUtf8.length == 0) {
            return EMPTY_BYTES;
        }
        byte[] prefix = new byte[nameUtf8.length + 1];
        System.arraycopy(nameUtf8, 0, prefix, 0, nameUtf8.length);
        prefix[nameUtf8.length] = '/';
        return prefix;
    }

    @FunctionalInterface
    private interface IntComparator {
        int compare(int a, int b);
    }

    /**
     * Sorts an {@code int[]} of indices using an iterative bottom-up merge
     * sort with a custom comparator. Operates directly on primitive
     * {@code int} values.
     */
    private static void mergeSort(int[] arr, int len, IntComparator cmp) {
        int[] tmp = new int[len];
        int[] src = arr, dst = tmp;
        for (int width = 1; width < len; width *= 2) {
            for (int lo = 0; lo < len; lo += width * 2) {
                int mid = Math.min(lo + width, len);
                int hi = Math.min(lo + width * 2, len);
                int i = lo, j = mid, k = lo;
                while (i < mid && j < hi) {
                    dst[k++] = cmp.compare(src[i], src[j]) <= 0 ? src[i++] : src[j++];
                }
                while (i < mid) {
                    dst[k++] = src[i++];
                }
                while (j < hi) {
                    dst[k++] = src[j++];
                }
            }
            int[] swap = src;
            src = dst;
            dst = swap;
        }
        if (src != arr) {
            System.arraycopy(src, 0, arr, 0, len);
        }
    }

    /**
     * Lexicographic comparison of two byte ranges using unsigned byte
     * values. Used by {@link #sortAndBuildFromCd} to sort entry names
     * referenced as (offset, length) pairs.
     */
    private static int compareBytesRange(byte[] a, int aOff, int aLen,
            byte[] b, int bOff, int bLen) {
        int minLen = Math.min(aLen, bLen);
        for (int i = 0; i < minLen; i++) {
            int diff = (a[aOff + i] & 0xFF) - (b[bOff + i] & 0xFF);
            if (diff != 0) {
                return diff;
            }
        }
        return aLen - bLen;
    }

    /**
     * Returns {@code true} if two byte ranges contain identical bytes.
     */
    private static boolean regionEquals(byte[] a, int aOff, byte[] b, int bOff, int len) {
        for (int i = 0; i < len; i++) {
            if (a[aOff + i] != b[bOff + i]) {
                return false;
            }
        }
        return true;
    }
}
