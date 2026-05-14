package io.quarkus.fs.util.rozip;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * A read-only {@link FileSystem} implementation for ZIP/JAR archives that is
 * immune to thread-interrupt-induced channel closures.
 * <p>
 * Unlike the JDK's {@code ZipFileSystem}, which uses a shared
 * {@link java.nio.channels.FileChannel} (an {@link java.nio.channels.InterruptibleChannel}),
 * this implementation reads through a {@link RandomAccessFile} whose I/O
 * operations are <em>not</em> affected by thread interrupts.
 * This eliminates the root cause of
 * <a href="https://bugs.openjdk.org/browse/JDK-8316882">JDK-8316882</a>.
 * <p>
 * Entry data is read on demand and decompressed into a byte array. STORED and
 * DEFLATED compression methods are supported. All reads are synchronized on
 * the underlying {@link RandomAccessFile} for thread safety.
 * <p>
 * Instances are created via the {@link #open(Path)} factory method.
 *
 * @see ReadOnlyZipPath
 * @see ReadOnlyZipFileSystemProvider
 */
public class ReadOnlyZipFileSystem extends FileSystem {

    private static final int LOCAL_HEADER_SIG = 0x04034b50;
    private static final int LOCAL_HEADER_FIXED_SIZE = 30;
    private static final int DATA_DESCRIPTOR_SIG = 0x08074b50;
    private static final int METHOD_STORED = 0;
    private static final int METHOD_DEFLATED = 8;

    static final long MAX_ENTRY_SIZE = 256 * 1024 * 1024L; // 256 MB
    private static final long MAX_COMPRESSION_RATIO = 1000;
    static boolean CACHE_ENABLED = Boolean.getBoolean("rozip.cache");

    private final Path zipPath;
    private final RandomAccessFile raf;
    private final CompactEntryTable entryTable;
    private final ReadOnlyZipPath rootPath;
    private final FileStore fileStore;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private static final int MAX_INFLATER_POOL_SIZE = 8;
    private final Deque<Inflater> inflaterPool = new ArrayDeque<>();
    private final ConcurrentHashMap<String, SoftReference<byte[]>> entryCache;

    /**
     * Opens a read-only, non-interruptible filesystem for the given ZIP/JAR file.
     * <p>
     * The central directory is parsed immediately. The underlying
     * {@link RandomAccessFile} remains open until {@link #close()} is called.
     * <p>
     * Each invocation opens a new, independent file handle — no caching or
     * deduplication is performed. Callers that need to avoid duplicate handles
     * for the same archive should cache the returned instance themselves.
     *
     * @param zipFile path to the ZIP or JAR file
     * @return a new {@link ReadOnlyZipFileSystem} instance
     * @throws IOException if the file cannot be read or is not a valid ZIP archive
     */
    public static ReadOnlyZipFileSystem open(Path zipFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(zipFile.toFile(), "r");
        try {
            ZipCentralDirectory cd = ZipCentralDirectory.parse(raf);
            return new ReadOnlyZipFileSystem(zipFile, raf, cd);
        } catch (IOException | RuntimeException e) {
            try {
                raf.close();
            } catch (IOException closeEx) {
                e.addSuppressed(closeEx);
            }
            throw e;
        }
    }

    /**
     * Constructs a new filesystem backed by the given archive file.
     *
     * @param zipPath path to the ZIP/JAR file on the default filesystem
     * @param raf an open file handle for reading entry data
     * @param cd the parsed central directory
     */
    private ReadOnlyZipFileSystem(Path zipPath, RandomAccessFile raf, ZipCentralDirectory cd) {
        this.zipPath = zipPath;
        this.raf = raf;
        this.entryTable = cd.entryTable();
        this.entryCache = CACHE_ENABLED ? new ConcurrentHashMap<>() : null;
        this.rootPath = new ReadOnlyZipPath(this, "/");
        this.fileStore = new ReadOnlyZipFileStore(zipPath);
    }

    /**
     * @return the singleton {@link ReadOnlyZipFileSystemProvider}
     */
    @Override
    public FileSystemProvider provider() {
        return ReadOnlyZipFileSystemProvider.INSTANCE;
    }

    /**
     * Closes this filesystem and releases the underlying file handle.
     * <p>
     * After closing, all operations on paths from this filesystem will throw
     * {@link ClosedFileSystemException}. This method is idempotent.
     */
    @Override
    public void close() throws IOException {
        if (open.compareAndSet(true, false)) {
            synchronized (raf) {
                raf.close();
            }
            synchronized (inflaterPool) {
                Inflater inf;
                while ((inf = inflaterPool.poll()) != null) {
                    inf.end();
                }
            }
            if (entryCache != null) {
                entryCache.clear();
            }
        }
    }

    /**
     * @return {@code true} if this filesystem has not been {@linkplain #close() closed}
     */
    @Override
    public boolean isOpen() {
        return open.get();
    }

    /**
     * @return {@code true} always; this filesystem does not support write operations
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * @return {@code "/"}, the path separator for ZIP entries
     */
    @Override
    public String getSeparator() {
        return "/";
    }

    /**
     * @return a single-element iterable containing the root path {@code "/"}
     */
    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singletonList(rootPath);
    }

    /**
     * @return a single-element iterable containing this archive's {@link FileStore}
     */
    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.singletonList(fileStore);
    }

    /**
     * @return the {@link FileStore} representing this archive
     */
    FileStore getFileStore() {
        return fileStore;
    }

    /**
     * @return a set containing only {@code "basic"}
     */
    @Override
    public Set<String> supportedFileAttributeViews() {
        return Set.of("basic");
    }

    /**
     * Creates a {@link ReadOnlyZipPath} by joining the given strings with
     * the {@code "/"} separator.
     *
     * @param first the first component
     * @param more additional components to join
     * @return a new path in this filesystem
     */
    @Override
    public Path getPath(String first, String... more) {
        ensureOpen();
        if (more.length == 0) {
            return new ReadOnlyZipPath(this, first);
        }
        StringBuilder sb = new StringBuilder(first);
        for (String s : more) {
            if (!sb.isEmpty() && sb.charAt(sb.length() - 1) != '/') {
                sb.append('/');
            }
            sb.append(s);
        }
        return new ReadOnlyZipPath(this, sb.toString());
    }

    /**
     * Returns a {@link PathMatcher} for the given syntax and pattern.
     * Supports {@code "glob:..."} and {@code "regex:..."} syntaxes.
     *
     * @param syntaxAndPattern the syntax and pattern string (e.g. {@code "glob:*.class"})
     * @return a path matcher
     * @throws IllegalArgumentException if the syntax is not supported
     * @throws java.util.regex.PatternSyntaxException if the pattern is invalid
     */
    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        int colon = syntaxAndPattern.indexOf(':');
        if (colon <= 0 || colon == syntaxAndPattern.length() - 1) {
            throw new IllegalArgumentException("Invalid syntax: " + syntaxAndPattern);
        }
        String syntax = syntaxAndPattern.substring(0, colon).toLowerCase();
        String pattern = syntaxAndPattern.substring(colon + 1);

        Pattern regex;
        if ("regex".equals(syntax)) {
            regex = Pattern.compile(pattern);
        } else if ("glob".equals(syntax)) {
            regex = Pattern.compile(globToRegex(pattern));
        } else {
            throw new UnsupportedOperationException("Syntax '" + syntax + "' not supported");
        }
        return path -> regex.matcher(path.toString()).matches();
    }

    /**
     * @throws UnsupportedOperationException always; ZIP archives have no user
     *         principal lookup service
     */
    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("UserPrincipalLookupService not supported");
    }

    /**
     * @throws UnsupportedOperationException always; ZIP archives cannot be
     *         watched for changes
     */
    @Override
    public WatchService newWatchService() throws IOException {
        throw new UnsupportedOperationException("WatchService not supported");
    }

    /**
     * @return the string form of the path to the underlying ZIP file
     */
    @Override
    public String toString() {
        return zipPath.toString();
    }

    /**
     * @return the cached root path {@code "/"}
     */
    ReadOnlyZipPath getRootPath() {
        return rootPath;
    }

    /**
     * @return the path to the underlying ZIP file on the default filesystem
     */
    Path getZipPath() {
        return zipPath;
    }

    /**
     * Constructs a {@code jar:} URI for an entry within this archive.
     *
     * @param absoluteEntryPath the absolute entry path (e.g. {@code "/com/example/Foo.class"})
     * @return a URI of the form {@code jar:file:///path/to/archive.jar!/entry/path}
     */
    URI entryUri(String absoluteEntryPath) {
        try {
            String encoded = new URI(null, null, absoluteEntryPath, null).getRawPath();
            return URI.create("jar:" + zipPath.toUri() + "!" + encoded);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid entry path: " + absoluteEntryPath, e);
        }
    }

    /**
     * Looks up entry metadata by name.
     *
     * @param entryName the entry name without leading {@code "/"} (empty string
     *        for root)
     * @return the entry info, or {@code null} if not found
     */
    ZipEntryInfo getEntryInfo(String entryName) {
        ensureOpen();
        return ZipEntryInfo.ROOT_ENTRY_NAME.equals(entryName) ? null : entryTable.getEntry(entryName);
    }

    /**
     * Returns the immediate children of a directory entry.
     *
     * @param entryName the directory entry name (empty string for root)
     * @return the list of child names, or {@code null} if not a directory
     */
    List<String> getDirectoryChildren(String entryName) {
        ensureOpen();
        return entryTable.getDirectoryChildren(entryName);
    }

    /**
     * Checks whether the given entry name exists (either as a file, an
     * explicit directory, an implicit directory, or the root).
     *
     * @param entryName the entry name
     * @return {@code true} if the entry exists
     */
    public boolean entryExists(String entryName) {
        ensureOpen();
        entryName = normalizeEntryName(entryName);
        return entryName.isEmpty() || entryTable.exists(entryName);
    }

    /**
     * Strips a leading and/or trailing {@code '/'} from an entry name,
     * converting path-style names (e.g. {@code "/com/example/"}) to the
     * slash-free form used by {@link CompactEntryTable} (e.g.
     * {@code "com/example"}). Returns the empty string for the root
     * ({@code "/"} or {@code ""}).
     */
    static String normalizeEntryName(String name) {
        if (name.isEmpty()) {
            return name;
        }
        if (name.charAt(0) == '/') {
            if (name.length() == 1) {
                return ZipEntryInfo.ROOT_ENTRY_NAME;
            }
            return name.charAt(name.length() - 1) == '/' ? name.substring(1, name.length() - 1) : name.substring(1);
        }
        return name.charAt(name.length() - 1) == '/' ? name.substring(0, name.length() - 1) : name;
    }

    /**
     * Returns {@link BasicFileAttributes} for the given entry name.
     *
     * @param entryName the entry name (empty string for root)
     * @return the attributes
     * @throws NoSuchFileException if the entry does not exist
     */
    BasicFileAttributes getAttributes(String entryName) throws NoSuchFileException {
        ensureOpen();
        if (ZipEntryInfo.ROOT_ENTRY_NAME.equals(entryName)) {
            return ReadOnlyZipAttributes.ROOT;
        }
        ZipEntryInfo info = entryTable.getEntry(entryName);
        if (info != null) {
            return new ReadOnlyZipAttributes(info);
        }
        if (entryTable.hasEntriesUnder(entryName)) {
            return ReadOnlyZipAttributes.ROOT;
        }
        throw new NoSuchFileException(entryName);
    }

    /**
     * Reads and decompresses the data for the given entry.
     * <p>
     * The compressed bytes are read from the underlying {@link RandomAccessFile}
     * within a synchronized block. If the entry uses DEFLATED compression, the
     * data is decompressed using {@link Inflater} before being returned.
     *
     * @param entryName the entry name
     * @return the uncompressed entry data
     * @throws NoSuchFileException if the entry does not exist
     * @throws IOException if the entry is a directory or an I/O error occurs
     */
    byte[] readEntryData(String entryName) throws IOException {
        ensureOpen();
        ZipEntryInfo info = entryTable.getEntry(entryName);
        if (info == null) {
            if (entryTable.hasEntriesUnder(entryName)) {
                throw new FileSystemException(entryName, null, "is a directory");
            }
            throw new NoSuchFileException(entryName);
        }
        if (info.directory()) {
            throw new FileSystemException(entryName, null, "is a directory");
        }

        if (entryCache != null) {
            SoftReference<byte[]> ref = entryCache.get(entryName);
            if (ref != null) {
                byte[] cached = ref.get();
                if (cached != null) {
                    return cached;
                }
            }
        }

        validateEntrySize(info);
        CompressedEntry ce = readCompressedData(info);
        byte[] result = decompress(ce, info);
        if (entryCache != null) {
            entryCache.put(entryName, new SoftReference<>(result));
        }
        return result;
    }

    /**
     * Holds the compressed bytes read from the archive together with the
     * best-known uncompressed size resolved from the local file header or
     * data descriptor.
     *
     * @param data the raw (possibly compressed) entry bytes
     * @param uncompressedSize uncompressed size from the local header or data
     *        descriptor, or 0 if neither source provided it
     */
    private record CompressedEntry(byte[] data, long uncompressedSize) {
    }

    /**
     * Reads the compressed data bytes for an entry from the archive.
     * <p>
     * Seeks to the local file header, reads it to determine the actual data
     * offset (which may differ from the central directory due to differing
     * extra field lengths), then reads the compressed data.
     * <p>
     * Also resolves the uncompressed size when the central directory reports 0.
     * The local header (offset 22) is checked first. If it is also 0 and the
     * general-purpose bit 3 is set (data descriptor present), the data
     * descriptor immediately following the compressed data is read to obtain
     * the actual uncompressed size. This lets {@link #decompress} use the
     * efficient pre-allocated inflate path in virtually all cases.
     *
     * @param info the entry metadata from the central directory
     * @return compressed bytes together with the resolved uncompressed size
     * @throws IOException if the file cannot be read or the local header is invalid
     */
    private CompressedEntry readCompressedData(ZipEntryInfo info) throws IOException {
        // ensureOpen inside synchronized(raf) guarantees the file handle cannot
        // be closed between the check and the reads — close() also syncs on raf
        synchronized (raf) {
            ensureOpen();
            raf.seek(info.localHeaderOffset());
            byte[] localHeader = new byte[LOCAL_HEADER_FIXED_SIZE];
            raf.readFully(localHeader);
            validateLocalHeader(localHeader, info);

            long localUncompressedSize = LittleEndian.readUint32(localHeader, 22);
            int nameLen = LittleEndian.readUint16(localHeader, 26);
            int extraLen = LittleEndian.readUint16(localHeader, 28);
            long dataOffset = info.localHeaderOffset() + LOCAL_HEADER_FIXED_SIZE + nameLen + extraLen;

            raf.seek(dataOffset);
            byte[] compressed = new byte[checkedCast(info.compressedSize())];
            raf.readFully(compressed);

            long uncompressedSize = localUncompressedSize;
            if (uncompressedSize == 0 && info.uncompressedSize() == 0) {
                int flags = LittleEndian.readUint16(localHeader, 6);
                if ((flags & 0x08) != 0) {
                    boolean zip64 = LittleEndian.readUint16(localHeader, 4) >= 45;
                    uncompressedSize = readDataDescriptorUncompressedSize(zip64);
                }
            }

            return new CompressedEntry(compressed, uncompressedSize);
        }
    }

    /**
     * Reads the uncompressed size from a data descriptor at the current
     * file position. Handles both the variant with a leading signature
     * ({@code 0x08074b50}) and the variant without, and both 32-bit and
     * 64-bit (ZIP64) field sizes.
     *
     * @param zip64 {@code true} if the entry uses ZIP64 format (8-byte fields)
     * @return the uncompressed size from the data descriptor
     * @throws IOException if the descriptor cannot be read
     */
    private long readDataDescriptorUncompressedSize(boolean zip64) throws IOException {
        if (zip64) {
            // ZIP64: signature?(4) + crc32(4) + compressedSize(8) + uncompressedSize(8)
            byte[] desc = new byte[24];
            raf.readFully(desc);
            if (LittleEndian.readInt32(desc, 0) == DATA_DESCRIPTOR_SIG) {
                return LittleEndian.readUint64(desc, 16);
            }
            // no signature: crc32(4) + compressedSize(8) + uncompressedSize(8)
            // we read 24 bytes but only need 20; the last 4 are unused
            return LittleEndian.readUint64(desc, 12);
        }
        byte[] desc = new byte[16];
        raf.readFully(desc);
        if (LittleEndian.readInt32(desc, 0) == DATA_DESCRIPTOR_SIG) {
            // signature(4) + crc32(4) + compressedSize(4) + uncompressedSize(4)
            return LittleEndian.readUint32(desc, 12);
        }
        // no signature: crc32(4) + compressedSize(4) + uncompressedSize(4)
        return LittleEndian.readUint32(desc, 8);
    }

    /**
     * Validates the local file header signature.
     */
    private static void validateLocalHeader(byte[] header, ZipEntryInfo info) throws IOException {
        int sig = LittleEndian.readInt32(header, 0);
        if (sig != LOCAL_HEADER_SIG) {
            throw new IOException("Invalid local file header signature for entry: " + info.name());
        }
    }

    /**
     * Decompresses entry data according to its compression method.
     * <p>
     * For DEFLATED entries, uses the uncompressed size from the central
     * directory when available, otherwise falls back to the size resolved
     * by {@link #readCompressedData} (from the local header or data
     * descriptor). Genuinely empty entries (uncompressed size and CRC both
     * 0) are returned immediately without inflating.
     *
     * @param ce the compressed bytes and resolved uncompressed size
     * @param info the entry metadata from the central directory
     * @return the uncompressed bytes
     * @throws IOException if decompression fails or the method is unsupported
     */
    private byte[] decompress(CompressedEntry ce, ZipEntryInfo info) throws IOException {
        byte[] result;
        if (info.compressionMethod() == METHOD_STORED) {
            result = ce.data();
        } else if (info.compressionMethod() == METHOD_DEFLATED) {
            long uncompressedSize = info.uncompressedSize();
            if (uncompressedSize == 0 && ce.uncompressedSize() > 0) {
                uncompressedSize = ce.uncompressedSize();
            }
            if (uncompressedSize > MAX_ENTRY_SIZE) {
                throw new IOException("Entry too large (uncompressed " + uncompressedSize
                        + " bytes, limit " + MAX_ENTRY_SIZE + "): " + info.name());
            }
            if (uncompressedSize == 0 && info.crc32() == 0) {
                return new byte[0];
            }
            result = inflate(ce.data(), checkedCast(uncompressedSize));
        } else {
            throw new IOException("Unsupported compression method " + info.compressionMethod()
                    + " for entry: " + info.name());
        }
        verifyCrc32(result, info);
        return result;
    }

    /**
     * Validates that the entry's compressed and uncompressed sizes are within
     * limits and that the compression ratio is not suspiciously high.
     *
     * @param info the entry metadata
     * @throws IOException if any size check fails
     */
    private static void validateEntrySize(ZipEntryInfo info) throws IOException {
        if (info.uncompressedSize() > MAX_ENTRY_SIZE) {
            throw new IOException("Entry too large (uncompressed " + info.uncompressedSize()
                    + " bytes, limit " + MAX_ENTRY_SIZE + "): " + info.name());
        }
        if (info.compressedSize() > MAX_ENTRY_SIZE) {
            throw new IOException("Entry too large (compressed " + info.compressedSize()
                    + " bytes, limit " + MAX_ENTRY_SIZE + "): " + info.name());
        }
        if (info.compressedSize() > 0
                && info.uncompressedSize() / info.compressedSize() > MAX_COMPRESSION_RATIO) {
            throw new IOException("Suspicious compression ratio for entry: " + info.name());
        }
    }

    /**
     * Verifies that the CRC-32 of the decompressed data matches the value
     * recorded in the central directory.
     *
     * @param data the decompressed entry bytes
     * @param info the entry metadata containing the expected CRC-32
     * @throws IOException if the checksums do not match
     */
    private static void verifyCrc32(byte[] data, ZipEntryInfo info) throws IOException {
        CRC32 crc = new CRC32();
        crc.update(data);
        if (crc.getValue() != info.crc32()) {
            throw new IOException("CRC-32 mismatch for entry: " + info.name()
                    + " (expected " + Long.toHexString(info.crc32())
                    + ", got " + Long.toHexString(crc.getValue()) + ")");
        }
    }

    /**
     * Inflates DEFLATE-compressed data into a pre-allocated buffer.
     * <p>
     * When {@code uncompressedSize} is 0 (unknown), delegates to
     * {@link #inflateDynamic(byte[])} which uses a dynamically-growing buffer.
     *
     * @param compressed the raw DEFLATE-compressed bytes
     * @param uncompressedSize the expected decompressed size, or 0 if unknown
     * @return the decompressed bytes
     * @throws IOException if decompression fails or the actual size does not
     *         match the declared size
     */
    private byte[] inflate(byte[] compressed, int uncompressedSize) throws IOException {
        if (uncompressedSize == 0) {
            // this shouldn't happen for a spec compliant ZIP, this is a defensive measure
            // matching the default ZipFileSystem implementation
            return inflateDynamic(compressed);
        }
        Inflater inflater = borrowInflater();
        try {
            inflater.setInput(compressed);
            byte[] result = new byte[uncompressedSize];
            int offset = 0;
            while (offset < uncompressedSize) {
                int n = inflater.inflate(result, offset, uncompressedSize - offset);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary()) {
                        break;
                    }
                    throw new IOException("Inflater stalled; compressed data may be corrupt");
                }
                offset += n;
            }
            if (offset < uncompressedSize) {
                throw new IOException("Decompressed size (" + offset
                        + ") is less than declared (" + uncompressedSize + ")");
            }
            if (!inflater.finished()) {
                throw new IOException("Decompressed data exceeds declared size (" + uncompressedSize + ")");
            }
            return result;
        } catch (DataFormatException e) {
            throw new IOException("Failed to decompress entry data", e);
        } finally {
            returnInflater(inflater);
        }
    }

    /**
     * Inflates compressed data when the uncompressed size is unknown (reported
     * as 0 in both the central directory and local file header). This occurs
     * with entries written using data descriptors, where some ZIP tools omit
     * sizes from both headers.
     * <p>
     * Inflates directly into a dynamically-growing {@code byte[]} rather than
     * through a {@link java.io.ByteArrayOutputStream} with an intermediate
     * buffer, so each decompressed byte is written once into the result array
     * instead of being copied twice (intermediate buffer to BAOS, then
     * {@code toByteArray()}). Output is capped at {@link #MAX_ENTRY_SIZE} to
     * guard against zip bombs.
     *
     * @param compressed the raw DEFLATE-compressed bytes
     * @return the decompressed bytes
     * @throws IOException if decompression fails or the output exceeds {@link #MAX_ENTRY_SIZE}
     */
    private byte[] inflateDynamic(byte[] compressed) throws IOException {
        Inflater inflater = borrowInflater();
        try {
            inflater.setInput(compressed);
            int capacity = Math.max(compressed.length * 2, 256);
            byte[] result = new byte[capacity];
            int offset = 0;
            while (!inflater.finished()) {
                if (offset == result.length) {
                    int newCap = (int) Math.min((long) result.length * 2, MAX_ENTRY_SIZE + 1);
                    result = Arrays.copyOf(result, newCap);
                }
                int n = inflater.inflate(result, offset, result.length - offset);
                if (n == 0) {
                    if (inflater.finished() || inflater.needsDictionary()) {
                        break;
                    }
                    throw new IOException("Inflater stalled; compressed data may be corrupt");
                }
                offset += n;
                if (offset > MAX_ENTRY_SIZE) {
                    throw new IOException("Decompressed data exceeds maximum entry size (" + MAX_ENTRY_SIZE + ")");
                }
            }
            return (offset == result.length) ? result : Arrays.copyOf(result, offset);
        } catch (DataFormatException e) {
            throw new IOException("Failed to decompress entry data", e);
        } finally {
            returnInflater(inflater);
        }
    }

    /**
     * Returns a pooled {@link Inflater} configured for raw DEFLATE (no
     * zlib header), or creates a new one if the pool is empty.
     *
     * @return an {@link Inflater} ready for use
     */
    private Inflater borrowInflater() {
        synchronized (inflaterPool) {
            Inflater inf = inflaterPool.poll();
            if (inf != null) {
                return inf;
            }
        }
        return new Inflater(true);
    }

    /**
     * Returns an {@link Inflater} to the pool after use. If the filesystem
     * is closed or the pool is full, the inflater is ended instead.
     *
     * @param inf the inflater to return
     */
    private void returnInflater(Inflater inf) {
        synchronized (inflaterPool) {
            if (open.get() && inflaterPool.size() < MAX_INFLATER_POOL_SIZE) {
                inf.reset();
                inflaterPool.push(inf);
                return;
            }
        }
        inf.end();
    }

    /**
     * @throws ClosedFileSystemException if this filesystem has been closed
     */
    private void ensureOpen() {
        if (!open.get()) {
            throw new ClosedFileSystemException();
        }
    }

    /**
     * Safely casts a {@code long} to {@code int}, throwing if the value
     * exceeds {@link Integer#MAX_VALUE}.
     */
    private static int checkedCast(long value) throws IOException {
        if (value < 0 || value > Integer.MAX_VALUE) {
            throw new IOException("Entry size too large: " + value);
        }
        return (int) value;
    }

    /**
     * Converts a glob pattern to a Java regex pattern.
     * <p>
     * Supported glob constructs:
     * <ul>
     * <li>{@code *} — matches zero or more characters within a single path segment</li>
     * <li>{@code **} — matches zero or more path segments (crosses {@code /} boundaries)</li>
     * <li>{@code ?} — matches exactly one non-separator character</li>
     * <li>{@code [abc]} — character class matching any one of the enclosed characters</li>
     * <li>{@code {a,b}} — brace alternation, may be nested (e.g. {@code {a,{b,c}}})</li>
     * <li>{@code \x} — escapes the next character, treating it as a literal</li>
     * </ul>
     * Regex metacharacters ({@code . ( ) + | ^ $ @ %}) are escaped automatically.
     *
     * @param glob the glob pattern to convert
     * @return a regex string anchored with {@code ^} and {@code $}
     */
    static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        int braceDepth = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*':
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        i += 2;
                        if (i < glob.length() && glob.charAt(i) == '/') {
                            regex.append("(.*/)?");
                            i++;
                        } else {
                            regex.append(".+");
                        }
                    } else {
                        regex.append("[^/]*");
                        i++;
                    }
                    break;
                case '?':
                    regex.append("[^/]");
                    i++;
                    break;
                case '[':
                    regex.append('[');
                    i++;
                    if (i < glob.length() && glob.charAt(i) == '!') {
                        regex.append('^');
                        i++;
                    }
                    while (i < glob.length() && glob.charAt(i) != ']') {
                        regex.append(glob.charAt(i));
                        i++;
                    }
                    if (i < glob.length()) {
                        regex.append(']');
                        i++;
                    }
                    break;
                case '{':
                    regex.append('(');
                    braceDepth++;
                    i++;
                    break;
                case '}':
                    regex.append(')');
                    braceDepth--;
                    i++;
                    break;
                case ',':
                    regex.append(braceDepth > 0 ? '|' : ',');
                    i++;
                    break;
                case '\\':
                    if (i + 1 < glob.length()) {
                        regex.append('\\').append(glob.charAt(i + 1));
                        i += 2;
                    } else {
                        regex.append('\\');
                        i++;
                    }
                    break;
                default:
                    if (".()+|^$@%".indexOf(c) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(c);
                    i++;
                    break;
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
