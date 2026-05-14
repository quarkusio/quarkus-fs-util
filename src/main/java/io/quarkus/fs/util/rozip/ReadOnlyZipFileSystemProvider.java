package io.quarkus.fs.util.rozip;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link FileSystemProvider} for the read-only, non-interruptible ZIP filesystem.
 * <p>
 * This provider is <em>not</em> registered via {@link java.util.ServiceLoader};
 * it is only used programmatically through {@link ReadOnlyZipFileSystem#open(Path)}.
 * Each {@link ReadOnlyZipFileSystem} instance references a shared singleton of
 * this provider.
 * <p>
 * All write operations throw {@link ReadOnlyFileSystemException}.
 */
class ReadOnlyZipFileSystemProvider extends FileSystemProvider {

    static final ReadOnlyZipFileSystemProvider INSTANCE = new ReadOnlyZipFileSystemProvider();

    private ReadOnlyZipFileSystemProvider() {
    }

    /**
     * Returns {@code "jar"} to stay consistent with the JDK's ZIP filesystem
     * provider. This ensures that URIs produced by {@link ReadOnlyZipPath#toUri()}
     * use the standard {@code jar:file:///...} form and are recognizable by code
     * that inspects URI schemes. Because this provider is not registered via
     * {@link java.util.ServiceLoader}, the scheme is never used for provider lookup.
     *
     * @return {@code "jar"}
     */
    @Override
    public String getScheme() {
        return "jar";
    }

    /**
     * @throws UnsupportedOperationException always; use
     *         {@link ReadOnlyZipFileSystem#open(Path)} instead
     */
    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        throw new UnsupportedOperationException("Use ReadOnlyZipFileSystem.open(Path)");
    }

    /**
     * @throws UnsupportedOperationException always; use
     *         {@link ReadOnlyZipFileSystem#open(Path)} instead
     */
    @Override
    public FileSystem getFileSystem(URI uri) {
        throw new UnsupportedOperationException("Use ReadOnlyZipFileSystem.open(Path)");
    }

    /**
     * @throws UnsupportedOperationException always
     */
    @Override
    public Path getPath(URI uri) {
        throw new UnsupportedOperationException("Use ReadOnlyZipFileSystem.getPath(String)");
    }

    /**
     * Opens an {@link InputStream} to read the contents of the specified entry.
     * <p>
     * The entire entry is read and decompressed (if needed) into a byte array.
     * The returned stream reads from that array, so it does not hold any lock
     * on the underlying ZIP file.
     *
     * @param path the entry path
     * @param options open options (only {@link StandardOpenOption#READ} is accepted)
     * @return an input stream for reading the entry contents
     * @throws NoSuchFileException if the entry does not exist
     * @throws IOException if the entry is a directory or an I/O error occurs
     */
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        validateReadOptions(options);
        ReadOnlyZipFileSystem fs = toFs(path);
        String entryName = toEntryName(path);
        byte[] data = fs.readEntryData(entryName);
        return new ByteArrayInputStream(data);
    }

    /**
     * @throws ReadOnlyFileSystemException always
     */
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * Opens a {@link SeekableByteChannel} to read the contents of the specified
     * entry.
     *
     * @param path the entry path
     * @param options open options (only {@link StandardOpenOption#READ} is accepted)
     * @param attrs ignored
     * @return a seekable byte channel over the entry contents
     * @throws NoSuchFileException if the entry does not exist
     * @throws IOException if an I/O error occurs
     */
    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
            FileAttribute<?>... attrs) throws IOException {
        validateReadOptions(options);
        ReadOnlyZipFileSystem fs = toFs(path);
        String entryName = toEntryName(path);
        byte[] data = fs.readEntryData(entryName);
        return new ByteArrayChannel(data);
    }

    /**
     * Returns a {@link DirectoryStream} that iterates over the immediate
     * children of the given directory entry.
     *
     * @param dir the directory path
     * @param filter the filter to apply to each child
     * @return a directory stream of child paths
     * @throws NoSuchFileException if the directory does not exist
     * @throws IOException if the path is not a directory
     */
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir,
            DirectoryStream.Filter<? super Path> filter) throws IOException {
        ReadOnlyZipFileSystem fs = toFs(dir);
        String entryName = toEntryName(dir);
        List<String> children = fs.getDirectoryChildren(entryName);
        if (children == null) {
            ZipEntryInfo info = fs.getEntryInfo(entryName);
            if (info == null) {
                throw new NoSuchFileException(dir.toString());
            }
            throw new IOException("Not a directory: " + dir);
        }

        Path parent = ((ReadOnlyZipPath) dir).toAbsolutePath();
        return new DirectoryStream<>() {
            private volatile boolean open = true;
            private final AtomicBoolean iteratorReturned = new AtomicBoolean();

            @Override
            public Iterator<Path> iterator() {
                if (!open) {
                    throw new IllegalStateException("DirectoryStream is closed");
                }
                if (!iteratorReturned.compareAndSet(false, true)) {
                    throw new IllegalStateException("Iterator already obtained");
                }
                return new FilteredChildIterator(parent, children, filter, fs);
            }

            @Override
            public void close() throws IOException {
                open = false;
            }
        };
    }

    /**
     * Reads basic file attributes for the given path.
     *
     * @param path the entry path
     * @param type must be {@link BasicFileAttributes}{@code .class}
     * @param options ignored (no symlinks in ZIP)
     * @return the file attributes
     * @throws NoSuchFileException if the entry does not exist
     * @throws UnsupportedOperationException if {@code type} is not
     *         {@code BasicFileAttributes.class}
     */
    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type,
            LinkOption... options) throws IOException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException("Only BasicFileAttributes is supported");
        }
        ReadOnlyZipFileSystem fs = toFs(path);
        String entryName = toEntryName(path);
        return (A) fs.getAttributes(entryName);
    }

    /**
     * Reads file attributes as a name-value map.
     *
     * @param path the entry path
     * @param attributes a comma-separated list of attribute names (e.g. {@code "basic:size,isDirectory"})
     * @param options ignored
     * @return a map of attribute names to values
     * @throws NoSuchFileException if the entry does not exist
     */
    @Override
    public Map<String, Object> readAttributes(Path path, String attributes,
            LinkOption... options) throws IOException {
        BasicFileAttributes attrs = readAttributes(path, BasicFileAttributes.class, options);
        return attributesToMap(attrs, attributes);
    }

    /**
     * Checks that the given entry exists and that the requested access modes
     * are compatible with a read-only filesystem.
     *
     * @param path the entry path
     * @param modes the access modes to check
     * @throws NoSuchFileException if the entry does not exist
     * @throws AccessDeniedException if {@link AccessMode#WRITE} is requested
     */
    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        for (AccessMode mode : modes) {
            if (mode == AccessMode.WRITE) {
                throw new AccessDeniedException(path.toString(), null, "Read-only filesystem");
            }
        }
        ReadOnlyZipFileSystem fs = toFs(path);
        String entryName = toEntryName(path);
        if (!fs.entryExists(entryName)) {
            throw new NoSuchFileException(path.toString());
        }
    }

    /**
     * Returns a {@link BasicFileAttributeView} for the given path if requested.
     *
     * @param path the entry path
     * @param type must be {@link BasicFileAttributeView}{@code .class}
     * @param options ignored
     * @return the attribute view, or {@code null} if the type is not supported
     */
    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type,
            LinkOption... options) {
        if (type != BasicFileAttributeView.class) {
            return null;
        }
        return (V) new ReadOnlyBasicFileAttributeView(path, this);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        if (path.equals(path2)) {
            return true;
        }
        if (!(path instanceof ReadOnlyZipPath) || !(path2 instanceof ReadOnlyZipPath)) {
            return false;
        }
        ReadOnlyZipFileSystem fs1 = toFs(path);
        ReadOnlyZipFileSystem fs2 = toFs(path2);
        if (fs1 != fs2 && !fs1.getZipPath().toRealPath().equals(fs2.getZipPath().toRealPath())) {
            return false;
        }
        return toEntryName(path).equals(toEntryName(path2));
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public java.nio.file.FileStore getFileStore(Path path) throws IOException {
        return toFs(path).getFileStore();
    }

    /** @throws ReadOnlyFileSystemException always */
    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /** @throws ReadOnlyFileSystemException always */
    @Override
    public void delete(Path path) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /** @throws ReadOnlyFileSystemException always */
    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /** @throws ReadOnlyFileSystemException always */
    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /** @throws ReadOnlyFileSystemException always */
    @Override
    public void setAttribute(Path path, String attribute, Object value,
            LinkOption... options) throws IOException {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * Extracts the {@link ReadOnlyZipFileSystem} from the given path.
     */
    private static ReadOnlyZipFileSystem toFs(Path path) {
        return (ReadOnlyZipFileSystem) path.getFileSystem();
    }

    /**
     * Converts a {@link ReadOnlyZipPath} to the internal entry name used by
     * the filesystem index. Strips the leading {@code "/"} from absolute paths
     * and normalizes directory names.
     *
     * @param path the path
     * @return the entry name (e.g. {@code "com/example/Foo.class"} or {@code ""}
     *         for root)
     */
    private static String toEntryName(Path path) {
        return ReadOnlyZipFileSystem.normalizeEntryName(path.toAbsolutePath().normalize().toString());
    }

    /**
     * Validates that the given open options are compatible with read-only access.
     *
     * @throws ReadOnlyFileSystemException if any write option is present
     */
    private static void validateReadOptions(OpenOption... options) {
        validateReadOptions(List.of(options));
    }

    /**
     * Validates that the given open option set is compatible with read-only access.
     *
     * @throws ReadOnlyFileSystemException if any write option is present
     */
    private static void validateReadOptions(Iterable<? extends OpenOption> options) {
        for (OpenOption opt : options) {
            if (opt == StandardOpenOption.WRITE
                    || opt == StandardOpenOption.APPEND
                    || opt == StandardOpenOption.TRUNCATE_EXISTING
                    || opt == StandardOpenOption.CREATE
                    || opt == StandardOpenOption.CREATE_NEW) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    /**
     * Converts {@link BasicFileAttributes} to a name-value map based on the
     * requested attribute string.
     */
    private static Map<String, Object> attributesToMap(BasicFileAttributes attrs, String spec) {
        String prefix = "";
        String names = spec;
        int colon = spec.indexOf(':');
        if (colon >= 0) {
            prefix = spec.substring(0, colon);
            names = spec.substring(colon + 1);
        }
        if (!prefix.isEmpty() && !prefix.equals("basic")) {
            throw new UnsupportedOperationException("View '" + prefix + "' not supported");
        }

        if (names.equals("*")) {
            Map<String, Object> map = new HashMap<>(9);
            map.put("lastModifiedTime", attrs.lastModifiedTime());
            map.put("lastAccessTime", attrs.lastAccessTime());
            map.put("creationTime", attrs.creationTime());
            map.put("isRegularFile", attrs.isRegularFile());
            map.put("isDirectory", attrs.isDirectory());
            map.put("isSymbolicLink", attrs.isSymbolicLink());
            map.put("isOther", attrs.isOther());
            map.put("size", attrs.size());
            map.put("fileKey", attrs.fileKey());
            return map;
        }
        String[] requested = names.split(",");
        Map<String, Object> map = new HashMap<>(requested.length * 2);
        for (String name : requested) {
            name = name.trim();
            switch (name) {
                case "lastModifiedTime":
                    map.put(name, attrs.lastModifiedTime());
                    break;
                case "lastAccessTime":
                    map.put(name, attrs.lastAccessTime());
                    break;
                case "creationTime":
                    map.put(name, attrs.creationTime());
                    break;
                case "isRegularFile":
                    map.put(name, attrs.isRegularFile());
                    break;
                case "isDirectory":
                    map.put(name, attrs.isDirectory());
                    break;
                case "isSymbolicLink":
                    map.put(name, attrs.isSymbolicLink());
                    break;
                case "isOther":
                    map.put(name, attrs.isOther());
                    break;
                case "size":
                    map.put(name, attrs.size());
                    break;
                case "fileKey":
                    map.put(name, attrs.fileKey());
                    break;
                default:
                    throw new IllegalArgumentException("Unknown attribute: " + name);
            }
        }
        return map;
    }

    /**
     * An iterator over directory children that applies a filter and produces
     * absolute {@link ReadOnlyZipPath} instances.
     */
    private static class FilteredChildIterator implements Iterator<Path> {

        private final Path parent;
        private final List<String> children;
        private final DirectoryStream.Filter<? super Path> filter;
        private final ReadOnlyZipFileSystem fs;
        private int index;
        private Path next;

        FilteredChildIterator(Path parent, List<String> children,
                DirectoryStream.Filter<? super Path> filter,
                ReadOnlyZipFileSystem fs) {
            this.parent = parent;
            this.children = children;
            this.filter = filter;
            this.fs = fs;
            advance();
        }

        @Override
        public boolean hasNext() {
            return next != null;
        }

        @Override
        public Path next() {
            if (next == null) {
                throw new NoSuchElementException();
            }
            Path result = next;
            advance();
            return result;
        }

        private void advance() {
            next = null;
            while (index < children.size()) {
                String child = children.get(index++);
                Path p = parent.resolve(child);
                try {
                    if (filter == null || filter.accept(p)) {
                        next = p;
                        return;
                    }
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        }
    }

    /**
     * Read-only {@link BasicFileAttributeView} implementation.
     */
    private static class ReadOnlyBasicFileAttributeView implements BasicFileAttributeView {

        private final Path path;
        private final ReadOnlyZipFileSystemProvider provider;

        ReadOnlyBasicFileAttributeView(Path path, ReadOnlyZipFileSystemProvider provider) {
            this.path = path;
            this.provider = provider;
        }

        @Override
        public String name() {
            return "basic";
        }

        @Override
        public BasicFileAttributes readAttributes() throws IOException {
            return provider.readAttributes(path, BasicFileAttributes.class);
        }

        /** @throws ReadOnlyFileSystemException always */
        @Override
        public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime,
                FileTime createTime) throws IOException {
            throw new ReadOnlyFileSystemException();
        }
    }

    /**
     * A read-only {@link SeekableByteChannel} backed by a byte array.
     * <p>
     * This class is not thread-safe. Each call to
     * {@link #newByteChannel(Path, Set, FileAttribute[])} creates a fresh
     * instance, so concurrent access is not an issue in normal usage.
     */
    static class ByteArrayChannel implements SeekableByteChannel {

        private final byte[] data;
        private int position;
        private boolean open = true;

        ByteArrayChannel(byte[] data) {
            this.data = data;
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            ensureOpen();
            if (position >= data.length) {
                return -1;
            }
            int remaining = data.length - position;
            int toRead = Math.min(dst.remaining(), remaining);
            dst.put(data, position, toRead);
            position += toRead;
            return toRead;
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            throw new NonWritableChannelException();
        }

        @Override
        public long position() throws IOException {
            ensureOpen();
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) throws IOException {
            ensureOpen();
            if (newPosition < 0 || newPosition > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Invalid position: " + newPosition);
            }
            this.position = (int) newPosition;
            return this;
        }

        @Override
        public long size() throws IOException {
            ensureOpen();
            return data.length;
        }

        @Override
        public SeekableByteChannel truncate(long size) throws IOException {
            throw new NonWritableChannelException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() throws IOException {
            open = false;
        }

        private void ensureOpen() throws ClosedChannelException {
            if (!open) {
                throw new ClosedChannelException();
            }
        }
    }
}
