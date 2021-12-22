package io.quarkus.fs.util.base;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Base Implementation of a FileSystemProvider delegating most operations to another FileSystemProvider.
 */
public abstract class DelegatingFileSystemProvider extends FileSystemProvider {
    protected final FileSystemProvider delegate;

    /**
     *
     * @param delegate the FileSystemProvider to delegate to. May not be null
     */
    protected DelegatingFileSystemProvider(FileSystemProvider delegate) {
        this.delegate = delegate;
    }

    @Override
    public String getScheme() {
        return delegate.getScheme();
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return delegate.newFileSystem(uri, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        return delegate.getFileSystem(uri);
    }

    @Override
    public Path getPath(URI uri) {
        return delegate.getPath(uri);
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        return delegate.newFileSystem(DelegatingPath.unwrap(path), env);
    }

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        return delegate.newInputStream(DelegatingPath.unwrap(path), options);
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) throws IOException {
        return delegate.newOutputStream(DelegatingPath.unwrap(path), options);
    }

    @Override
    public FileChannel newFileChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        return delegate.newFileChannel(DelegatingPath.unwrap(path), options, attrs);
    }

    @Override
    public AsynchronousFileChannel newAsynchronousFileChannel(Path path, Set<? extends OpenOption> options,
            ExecutorService executor, FileAttribute<?>... attrs) throws IOException {
        return delegate.newAsynchronousFileChannel(DelegatingPath.unwrap(path), options, executor, attrs);
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
            throws IOException {
        return delegate.newByteChannel(DelegatingPath.unwrap(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        return delegate.newDirectoryStream(DelegatingPath.unwrap(dir), filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException {
        delegate.createDirectory(DelegatingPath.unwrap(dir), attrs);
    }

    @Override
    public void createSymbolicLink(Path link, Path target, FileAttribute<?>... attrs) throws IOException {
        delegate.createSymbolicLink(DelegatingPath.unwrap(link), DelegatingPath.unwrap(target), attrs);
    }

    @Override
    public void createLink(Path link, Path existing) throws IOException {
        delegate.createLink(DelegatingPath.unwrap(link), DelegatingPath.unwrap(existing));
    }

    @Override
    public void delete(Path path) throws IOException {
        delegate.delete(DelegatingPath.unwrap(path));
    }

    @Override
    public boolean deleteIfExists(Path path) throws IOException {
        return delegate.deleteIfExists(DelegatingPath.unwrap(path));
    }

    @Override
    public Path readSymbolicLink(Path link) throws IOException {
        return delegate.readSymbolicLink(link);
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException {
        delegate.copy(DelegatingPath.unwrap(source), DelegatingPath.unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException {
        delegate.move(DelegatingPath.unwrap(source), DelegatingPath.unwrap(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return delegate.isSameFile(DelegatingPath.unwrap(path), DelegatingPath.unwrap(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException {
        return delegate.isHidden(DelegatingPath.unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return delegate.getFileStore(DelegatingPath.unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        delegate.checkAccess(DelegatingPath.unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        return delegate.getFileAttributeView(DelegatingPath.unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        return delegate.readAttributes(DelegatingPath.unwrap(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        return delegate.readAttributes(DelegatingPath.unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        delegate.setAttribute(DelegatingPath.unwrap(path), attribute, value, options);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        DelegatingFileSystemProvider that = (DelegatingFileSystemProvider) o;
        return Objects.equals(delegate, that.delegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate);
    }
}
