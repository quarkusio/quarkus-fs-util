package io.quarkus.fs.util.base;

import io.quarkus.fs.util.sysfs.PathWrapper;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Iterator;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;

/**
 * Base Implementation of a Path delegating most operations to another Path.
 */
public abstract class DelegatingPath implements Path {
    protected final Path delegate;

    private volatile URI uri;

    /**
     * @param delegate the Path to delegate to. May not be null.
     */
    protected DelegatingPath(Path delegate) {
        this.delegate = delegate;
    }

    /**
     * Helper method to unwrap a {@link DelegatingPath} to the specific Path implementation. Some providers do internal checks
     * to make
     * sure that a Path is compatible with a FS. E.g. a WindowsFileSystem only works with WindowsPaths
     *
     * @param path
     * @return unwrapped path, or the original one if not an instance of DelegatingPath
     */
    public static Path unwrap(Path path) {
        if (path instanceof PathWrapper) {
            return ((PathWrapper) path).getDelegate();
        }
        return path;
    }

    public Path getDelegate() {
        return delegate;
    }

    @Override
    public boolean startsWith(String other) {
        return delegate.startsWith(other);
    }

    @Override
    public boolean endsWith(String other) {
        return delegate.endsWith(other);
    }

    @Override
    public Path resolve(String other) {
        return delegate.resolve(other);
    }

    @Override
    public Path resolveSibling(Path other) {
        return delegate.resolveSibling(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return delegate.resolveSibling(other);
    }

    @Override
    public File toFile() {
        return delegate.toFile();
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        return delegate.register(watcher, events);
    }

    @Override
    public Iterator<Path> iterator() {
        return delegate.iterator();
    }

    @Override
    public FileSystem getFileSystem() {
        return delegate.getFileSystem();
    }

    @Override
    public boolean isAbsolute() {
        return delegate.isAbsolute();
    }

    @Override
    public Path getRoot() {
        return delegate.getRoot();
    }

    @Override
    public Path getFileName() {
        return delegate.getFileName();
    }

    @Override
    public Path getParent() {
        return delegate.getParent();
    }

    @Override
    public int getNameCount() {
        return delegate.getNameCount();
    }

    @Override
    public Path getName(int index) {
        return delegate.getName(index);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        return delegate.subpath(beginIndex, endIndex);
    }

    @Override
    public boolean startsWith(Path other) {
        return delegate.startsWith(other);
    }

    @Override
    public boolean endsWith(Path other) {
        return delegate.endsWith(other);
    }

    @Override
    public Path normalize() {
        return delegate.normalize();
    }

    @Override
    public Path resolve(Path other) {
        return delegate.resolve(other);
    }

    @Override
    public Path relativize(Path other) {
        return delegate.relativize(other);
    }

    @Override
    public URI toUri() {
        if (uri == null) {
            uri = delegate.toUri();
        }
        return uri;
    }

    @Override
    public Path toAbsolutePath() {
        return delegate.toAbsolutePath();
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return delegate.toRealPath(options);
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
            throws IOException {
        return delegate.register(watcher, events, modifiers);
    }

    @Override
    public int compareTo(Path other) {
        return delegate.compareTo(other);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    @Override
    public void forEach(Consumer<? super Path> action) {
        delegate.forEach(action);
    }

    @Override
    public Spliterator<Path> spliterator() {
        return delegate.spliterator();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        DelegatingPath that = (DelegatingPath) o;
        return Objects.equals(delegate, that.delegate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(delegate);
    }
}
