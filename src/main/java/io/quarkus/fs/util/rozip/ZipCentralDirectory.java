package io.quarkus.fs.util.rozip;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * Parses the ZIP central directory from a {@link RandomAccessFile} and produces
 * an entry index and directory tree.
 * <p>
 * The parser handles both standard ZIP and ZIP64 formats. All I/O is performed
 * through the provided {@link RandomAccessFile}, which is <em>not</em> an
 * {@link java.nio.channels.InterruptibleChannel} and therefore immune to
 * thread-interrupt-induced channel closures.
 * <p>
 * After construction, the {@link RandomAccessFile} is not accessed by this
 * class; callers retain ownership of the file handle.
 * <p>
 * Entry names are decoded as UTF-8. Archives that use the legacy CP437
 * encoding (general-purpose bit 11 unset) may produce incorrect names.
 */
final class ZipCentralDirectory {

    // ZIP signature constants
    private static final int EOCD_SIG = 0x06054b50;
    private static final int ZIP64_EOCD_SIG = 0x06064b50;
    private static final int ZIP64_EOCD_LOCATOR_SIG = 0x07064b50;
    static final int CENTRAL_DIR_SIG = 0x02014b50;

    // EOCD sizes
    private static final int EOCD_MIN_SIZE = 22;
    private static final int EOCD_MAX_COMMENT = 65535;

    // Extra field tags
    private static final int ZIP64_EXTRA_TAG = 0x0001;
    private static final int NTFS_EXTRA_TAG = 0x000a;
    private static final int UNIX_EXTRA_TAG = 0x5455;

    // Milliseconds between the Windows FILETIME epoch (1601-01-01) and the Unix epoch (1970-01-01)
    private static final long WINDOWS_EPOCH_DIFF_MILLIS = 11_644_473_600_000L;

    private static final long MAX_CENTRAL_DIR_SIZE = 256 * 1024 * 1024L; // 256 MB

    private final CompactEntryTable entryTable;

    private ZipCentralDirectory(CompactEntryTable entryTable) {
        this.entryTable = entryTable;
    }

    /**
     * Parses the central directory of the ZIP file accessible through the given
     * {@link RandomAccessFile}.
     *
     * @param raf an open {@link RandomAccessFile} positioned anywhere; the method
     *        seeks as needed
     * @return a parsed central directory containing entry metadata and directory tree
     * @throws IOException if the file cannot be read or is not a valid ZIP archive
     */
    static ZipCentralDirectory parse(RandomAccessFile raf) throws IOException {
        long fileLength = raf.length();
        if (fileLength < EOCD_MIN_SIZE) {
            throw new IOException("File is too small to be a valid ZIP archive");
        }

        long[] eocdInfo = findEocd(raf, fileLength);
        long cdOffset = eocdInfo[0];
        long cdSize = eocdInfo[1];
        long totalEntries = eocdInfo[2];

        if (cdSize > MAX_CENTRAL_DIR_SIZE) {
            throw new IOException("Central directory too large (" + cdSize
                    + " bytes, limit " + MAX_CENTRAL_DIR_SIZE + ")");
        }
        byte[] cdBytes = new byte[(int) cdSize];
        raf.seek(cdOffset);
        raf.readFully(cdBytes);

        CompactEntryTable table = CompactEntryTable.buildFromCentralDirectory(
                cdBytes, (int) cdSize, totalEntries, cdOffset);
        return new ZipCentralDirectory(table);
    }

    CompactEntryTable entryTable() {
        return entryTable;
    }

    /**
     * Locates the End of Central Directory record and extracts the central
     * directory offset, size, and total entry count. Handles ZIP64 if needed.
     *
     * @return array of {@code [cdOffset, cdSize, totalEntries]}
     */
    private static long[] findEocd(RandomAccessFile raf, long fileLength) throws IOException {
        int searchLen = (int) Math.min(fileLength, EOCD_MIN_SIZE + EOCD_MAX_COMMENT);
        long searchStart = fileLength - searchLen;
        byte[] buf = new byte[searchLen];

        raf.seek(searchStart);
        raf.readFully(buf);

        int eocdPos = findEocdSignature(buf);
        if (eocdPos < 0) {
            throw new IOException("End of Central Directory signature not found; not a valid ZIP file");
        }

        long totalEntries = LittleEndian.readUint16(buf, eocdPos + 10);
        long cdSize = LittleEndian.readUint32(buf, eocdPos + 12);
        long cdOffset = LittleEndian.readUint32(buf, eocdPos + 16);

        if (needsZip64(totalEntries, cdSize, cdOffset)) {
            return readZip64Eocd(raf, searchStart + eocdPos);
        }

        return new long[] { cdOffset, cdSize, totalEntries };
    }

    /**
     * Scans the buffer backwards for the EOCD signature {@code PK\x05\x06}.
     *
     * @return offset within the buffer, or {@code -1} if not found
     */
    private static int findEocdSignature(byte[] buf) {
        for (int i = buf.length - EOCD_MIN_SIZE; i >= 0; i--) {
            if (LittleEndian.readInt32(buf, i) == EOCD_SIG) {
                return i;
            }
        }
        return -1;
    }

    /**
     * @return {@code true} if any EOCD field indicates ZIP64 is needed
     */
    private static boolean needsZip64(long totalEntries, long cdSize, long cdOffset) {
        return totalEntries == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL;
    }

    /**
     * Reads the ZIP64 End of Central Directory record and returns the 64-bit
     * central directory offset, size, and total entry count.
     *
     * @param raf the archive file
     * @param eocdOffset absolute file offset of the standard EOCD record
     * @return array of {@code [cdOffset, cdSize, totalEntries]}
     */
    private static long[] readZip64Eocd(RandomAccessFile raf, long eocdOffset) throws IOException {
        long locatorOffset = eocdOffset - 20;
        if (locatorOffset < 0) {
            throw new IOException("ZIP64 EOCD locator not found");
        }

        byte[] locator = new byte[20];
        raf.seek(locatorOffset);
        raf.readFully(locator);

        if (LittleEndian.readInt32(locator, 0) != ZIP64_EOCD_LOCATOR_SIG) {
            throw new IOException("ZIP64 EOCD locator signature mismatch");
        }

        long zip64EocdOffset = LittleEndian.readUint64(locator, 8);

        byte[] eocd64 = new byte[56];
        raf.seek(zip64EocdOffset);
        raf.readFully(eocd64);

        if (LittleEndian.readInt32(eocd64, 0) != ZIP64_EOCD_SIG) {
            throw new IOException("ZIP64 EOCD signature mismatch");
        }

        long totalEntries = LittleEndian.readUint64(eocd64, 32);
        long cdSize = LittleEndian.readUint64(eocd64, 40);
        long cdOffset = LittleEndian.readUint64(eocd64, 48);

        return new long[] { cdOffset, cdSize, totalEntries };
    }

    /**
     * @return {@code true} if any size or offset field contains the ZIP64
     *         sentinel value {@code 0xFFFFFFFF}
     */
    static boolean hasZip64Overrides(long compressedSize, long uncompressedSize, long localHeaderOffset) {
        return compressedSize == 0xFFFFFFFFL
                || uncompressedSize == 0xFFFFFFFFL
                || localHeaderOffset == 0xFFFFFFFFL;
    }

    /**
     * Reads 64-bit values from the ZIP64 extended information extra field.
     * <p>
     * The ZIP64 extra field stores values in order: uncompressed size, compressed
     * size, local header offset — but only for fields whose standard counterpart
     * contains the sentinel value {@code 0xFFFFFFFF}.
     *
     * @return array of {@code [uncompressedSize, compressedSize, localHeaderOffset]}
     *         with ZIP64 values replacing any sentinel values
     */
    static long[] readZip64Extra(byte[] extra, int offset, int extraLen,
            long uncompressedSize, long compressedSize, long localHeaderOffset) {
        int end = offset + extraLen;
        int pos = offset;
        while (pos + 4 <= end) {
            int tag = LittleEndian.readUint16(extra, pos);
            int dataSize = LittleEndian.readUint16(extra, pos + 2);
            if (tag == ZIP64_EXTRA_TAG) {
                int dataPos = pos + 4;
                int zip64End = Math.min(pos + 4 + dataSize, end);
                if (uncompressedSize == 0xFFFFFFFFL && dataPos + 8 <= zip64End) {
                    uncompressedSize = LittleEndian.readUint64(extra, dataPos);
                    dataPos += 8;
                }
                if (compressedSize == 0xFFFFFFFFL && dataPos + 8 <= zip64End) {
                    compressedSize = LittleEndian.readUint64(extra, dataPos);
                    dataPos += 8;
                }
                if (localHeaderOffset == 0xFFFFFFFFL && dataPos + 8 <= zip64End) {
                    localHeaderOffset = LittleEndian.readUint64(extra, dataPos);
                }
                break;
            }
            pos += 4 + dataSize;
        }
        return new long[] { uncompressedSize, compressedSize, localHeaderOffset };
    }

    /**
     * Scans the extra field for high-precision timestamps. Checks for NTFS
     * (tag {@code 0x000a}) and Unix extended timestamp (tag {@code 0x5455})
     * extra fields. Returns the best available last-modified time with
     * priority: NTFS &gt; Unix extended &gt; DOS fallback.
     *
     * @param cd the central directory byte array
     * @param extraOffset start of the extra field data
     * @param extraLen length of the extra field
     * @param dosFallback last-modified time from DOS fields (epoch millis)
     * @return last-modified time in epoch milliseconds
     */
    static long readTimestampFromExtra(byte[] cd, int extraOffset, int extraLen, long dosFallback) {
        int end = extraOffset + extraLen;
        int pos = extraOffset;
        long ntfsMillis = Long.MIN_VALUE;
        long unixMillis = Long.MIN_VALUE;

        while (pos + 4 <= end) {
            int tag = LittleEndian.readUint16(cd, pos);
            int dataSize = LittleEndian.readUint16(cd, pos + 2);
            int dataStart = pos + 4;
            int dataEnd = dataStart + dataSize;
            if (dataEnd > end) {
                break;
            }

            if (tag == NTFS_EXTRA_TAG) {
                // 4 bytes reserved, then attribute blocks
                int attrPos = dataStart + 4;
                while (attrPos + 4 <= dataEnd) {
                    int attrTag = LittleEndian.readUint16(cd, attrPos);
                    int attrSize = LittleEndian.readUint16(cd, attrPos + 2);
                    if (attrTag == 0x0001 && attrSize >= 8 && attrPos + 4 + 8 <= dataEnd) {
                        long winTime = LittleEndian.readUint64(cd, attrPos + 4);
                        if (winTime > 0) {
                            ntfsMillis = winTime / 10_000 - WINDOWS_EPOCH_DIFF_MILLIS;
                        }
                        break;
                    }
                    attrPos += 4 + attrSize;
                }
            } else if (tag == UNIX_EXTRA_TAG && dataSize >= 5) {
                int flags = cd[dataStart] & 0xFF;
                if ((flags & 0x01) != 0) {
                    // Signed 32-bit Unix time, consistent with the JDK
                    long unixSeconds = LittleEndian.readInt32(cd, dataStart + 1);
                    unixMillis = unixSeconds * 1000L;
                }
            }

            pos = dataEnd;
        }

        if (ntfsMillis != Long.MIN_VALUE) {
            return ntfsMillis;
        }
        if (unixMillis != Long.MIN_VALUE) {
            return unixMillis;
        }
        return dosFallback;
    }

    /**
     * Converts DOS date and time fields to epoch milliseconds.
     *
     * @param dosDate DOS-format date (bits: 15-9 = year-1980, 8-5 = month, 4-0 = day)
     * @param dosTime DOS-format time (bits: 15-11 = hour, 10-5 = minute, 4-0 = second/2)
     * @return epoch milliseconds in the system default time zone
     */
    static long dosToEpochMillis(int dosDate, int dosTime) {
        int year = ((dosDate >> 9) & 0x7F) + 1980;
        int month = (dosDate >> 5) & 0x0F;
        int day = dosDate & 0x1F;
        int hour = (dosTime >> 11) & 0x1F;
        int minute = (dosTime >> 5) & 0x3F;
        int second = (dosTime & 0x1F) * 2;

        if (month < 1)
            month = 1;
        if (day < 1)
            day = 1;

        try {
            // System default timezone, consistent with JDK's ZipFileSystem
            return LocalDateTime.of(year, month, day, hour, minute, second)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        } catch (Exception e) {
            return 0L;
        }
    }

}
