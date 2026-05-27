package io.quarkus.fs.util.rozip;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.ProviderMismatchException;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * {@link Path} implementation for entries within a {@link ReadOnlyZipFileSystem}.
 * <p>
 * All operations are pure string manipulation using {@code "/"} as the
 * separator. Absolute paths start with {@code "/"}, relative paths do not.
 * The root directory is represented by {@code "/"}.
 * <p>
 * Instances are created by {@link ReadOnlyZipFileSystem#getPath(String, String...)}.
 */
class ReadOnlyZipPath implements Path {

    private final ReadOnlyZipFileSystem fileSystem;
    private final String path;
    private volatile int[] offsets;

    /**
     * Creates a new path in the given filesystem.
     *
     * @param fileSystem the owning filesystem
     * @param path the normalized path string
     */
    ReadOnlyZipPath(ReadOnlyZipFileSystem fileSystem, String path) {
        this.fileSystem = fileSystem;
        this.path = path;
    }

    /**
     * @return the raw path string
     */
    String getPathString() {
        return path;
    }

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return path.startsWith("/");
    }

    @Override
    public Path getRoot() {
        return isAbsolute() ? fileSystem.getRootPath() : null;
    }

    @Override
    public Path getFileName() {
        if (path.isEmpty()) {
            return this;
        }

        int lastSlash = path.lastIndexOf('/');
        if (lastSlash == -1) {
            return this;
        }
        final int length = path.length();
        if (lastSlash == 0) {
            return length == 1 ? null : new ReadOnlyZipPath(fileSystem, path.substring(1));
        }
        if (lastSlash == length - 1) {
            lastSlash = path.lastIndexOf('/', lastSlash - 1);
            return lastSlash == -1 ? new ReadOnlyZipPath(fileSystem, path.substring(0, length - 1))
                    : new ReadOnlyZipPath(fileSystem, path.substring(lastSlash + 1, length - 1));
        }
        return new ReadOnlyZipPath(fileSystem, path.substring(lastSlash + 1));
    }

    @Override
    public Path getParent() {
        if (path.isEmpty() || path.equals("/")) {
            return null;
        }
        int end = path.length();
        if (path.charAt(end - 1) == '/') {
            end--;
        }
        int lastSlash = path.lastIndexOf('/', end - 1);
        if (lastSlash < 0) {
            return null;
        }
        if (lastSlash == 0) {
            return fileSystem.getRootPath();
        }
        return new ReadOnlyZipPath(fileSystem, path.substring(0, lastSlash));
    }

    @Override
    public int getNameCount() {
        return getOffsets().length;
    }

    @Override
    public Path getName(int index) {
        int[] offs = getOffsets();
        if (index < 0 || index >= offs.length) {
            throw new IllegalArgumentException("index: " + index);
        }
        return new ReadOnlyZipPath(fileSystem, componentAt(offs, index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        int[] offs = getOffsets();
        if (beginIndex < 0 || beginIndex >= offs.length
                || endIndex <= beginIndex || endIndex > offs.length) {
            throw new IllegalArgumentException("subpath(" + beginIndex + ", " + endIndex + ")");
        }
        if (endIndex - beginIndex == 1) {
            return new ReadOnlyZipPath(fileSystem, componentAt(offs, beginIndex));
        }
        StringBuilder sb = new StringBuilder();
        for (int i = beginIndex; i < endIndex; i++) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(componentAt(offs, i));
        }
        return new ReadOnlyZipPath(fileSystem, sb.toString());
    }

    @Override
    public boolean startsWith(Path other) {
        ReadOnlyZipPath o = toReadOnlyZipPath(other);
        if (o == null || this.isAbsolute() != o.isAbsolute()) {
            return false;
        }
        int[] thisOffs = getOffsets();
        int[] otherOffs = o.getOffsets();
        if (otherOffs.length > thisOffs.length) {
            return false;
        }
        // root starts with root
        if (o.path.equals("/") && this.isAbsolute()) {
            return true;
        }
        for (int i = 0; i < otherOffs.length; i++) {
            if (!componentEquals(thisOffs, i, o, otherOffs, i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(fileSystem.getPath(other));
    }

    @Override
    public boolean endsWith(Path other) {
        ReadOnlyZipPath o = toReadOnlyZipPath(other);
        if (o == null) {
            return false;
        }
        if (o.isAbsolute()) {
            return this.isAbsolute() && this.path.equals(o.path);
        }
        int[] thisOffs = getOffsets();
        int[] otherOffs = o.getOffsets();
        if (otherOffs.length > thisOffs.length) {
            return false;
        }
        int diff = thisOffs.length - otherOffs.length;
        for (int i = otherOffs.length - 1; i >= 0; i--) {
            if (!componentEquals(thisOffs, diff + i, o, otherOffs, i)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(fileSystem.getPath(other));
    }

    @Override
    public Path normalize() {
        if (path.indexOf('.') < 0) {
            return this;
        }
        int[] offs = getOffsets();
        if (offs.length == 0) {
            return this;
        }
        String[] components = new String[offs.length];
        for (int i = 0; i < offs.length; i++) {
            components[i] = componentAt(offs, i);
        }
        return buildNormalized(components, isAbsolute());
    }

    /**
     * Processes {@code "."} and {@code ".."} components and builds a new path.
     */
    private Path buildNormalized(String[] components, boolean absolute) {
        String[] result = new String[components.length];
        int len = 0;
        for (String c : components) {
            if (c.equals(".")) {
                continue;
            }
            if (c.equals("..")) {
                if (len > 0 && !result[len - 1].equals("..")) {
                    len--;
                } else if (!absolute) {
                    result[len++] = c;
                }
                continue;
            }
            result[len++] = c;
        }
        if (len == 0) {
            return absolute ? fileSystem.getRootPath() : new ReadOnlyZipPath(fileSystem, "");
        }
        StringBuilder sb = new StringBuilder();
        if (absolute) {
            sb.append('/');
        }
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(result[i]);
        }
        return new ReadOnlyZipPath(fileSystem, sb.toString());
    }

    @Override
    public Path resolve(Path other) {
        ReadOnlyZipPath o = requireSameFs(other);
        if (o.isAbsolute() || this.path.isEmpty()) {
            return o;
        }
        if (o.path.isEmpty()) {
            return this;
        }
        if (path.endsWith("/")) {
            return new ReadOnlyZipPath(fileSystem, path + o.path);
        }
        return new ReadOnlyZipPath(fileSystem, path + "/" + o.path);
    }

    @Override
    public Path resolve(String other) {
        return resolve(fileSystem.getPath(other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Objects.requireNonNull(other);
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(fileSystem.getPath(other));
    }

    @Override
    public Path relativize(Path other) {
        ReadOnlyZipPath o = requireSameFs(other);
        if (this.isAbsolute() != o.isAbsolute()) {
            throw new IllegalArgumentException("Cannot relativize paths with different roots");
        }
        if (this.path.equals(o.path)) {
            return new ReadOnlyZipPath(fileSystem, "");
        }
        int[] thisOffs = getOffsets();
        int[] otherOffs = o.getOffsets();
        int common = countCommonComponents(thisOffs, otherOffs, o);
        return buildRelativized(thisOffs, otherOffs, common, o);
    }

    /**
     * Counts the number of leading path components that {@code this} and
     * {@code other} have in common.
     */
    private int countCommonComponents(int[] thisOffs, int[] otherOffs, ReadOnlyZipPath other) {
        int minLen = Math.min(thisOffs.length, otherOffs.length);
        int common = 0;
        for (int i = 0; i < minLen; i++) {
            if (componentAt(thisOffs, i).equals(other.componentAt(otherOffs, i))) {
                common++;
            } else {
                break;
            }
        }
        return common;
    }

    /**
     * Builds the relativized path: {@code n} {@code ".."} components followed
     * by the remaining components of {@code other}.
     */
    private Path buildRelativized(int[] thisOffs, int[] otherOffs, int common, ReadOnlyZipPath other) {
        int dotDots = thisOffs.length - common;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < dotDots; i++) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append("..");
        }
        for (int i = common; i < otherOffs.length; i++) {
            if (!sb.isEmpty()) {
                sb.append('/');
            }
            sb.append(other.componentAt(otherOffs, i));
        }
        return new ReadOnlyZipPath(fileSystem, sb.toString());
    }

    @Override
    public URI toUri() {
        return fileSystem.entryUri(toAbsolutePath().toString());
    }

    @Override
    public Path toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        return new ReadOnlyZipPath(fileSystem, "/" + path);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return toAbsolutePath().normalize();
    }

    /**
     * @throws UnsupportedOperationException always; ZIP paths cannot be
     *         converted to {@link File}
     */
    @Override
    public File toFile() {
        throw new UnsupportedOperationException("ZIP path cannot be converted to File");
    }

    /**
     * @throws UnsupportedOperationException always; ZIP filesystems do not
     *         support watch services
     */
    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events,
            WatchEvent.Modifier... modifiers) throws IOException {
        throw new UnsupportedOperationException("Watch service not supported");
    }

    /**
     * @throws UnsupportedOperationException always; ZIP filesystems do not
     *         support watch services
     */
    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) throws IOException {
        throw new UnsupportedOperationException("Watch service not supported");
    }

    @Override
    public Iterator<Path> iterator() {
        return new Iterator<>() {
            private int index = 0;
            private final int count = getNameCount();

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public Path next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                return getName(index++);
            }
        };
    }

    @Override
    public int compareTo(Path other) {
        ReadOnlyZipPath o = requireSameFs(other);
        return this.path.compareTo(o.path);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof ReadOnlyZipPath other) {
            return fileSystem == other.fileSystem && path.equals(other.path);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return path.hashCode();
    }

    @Override
    public String toString() {
        return path;
    }

    /**
     * Lazily computes the offsets of each name component in the path string.
     * For path {@code "/a/b/c"}, the offsets point to {@code "a"}, {@code "b"},
     * {@code "c"}.
     */
    private int[] getOffsets() {
        int[] result = offsets;
        if (result == null) {
            result = computeOffsets();
            offsets = result;
        }
        return result;
    }

    /**
     * Computes name component offsets. Each offset is the start index of a
     * component in the path string, after skipping the leading {@code "/"}.
     */
    private int[] computeOffsets() {
        int len = path.length();
        if (len > 1 && path.charAt(len - 1) == '/') {
            len--;
        }
        if (len == 0 || (len == 1 && path.charAt(0) == '/')) {
            return new int[0];
        }
        int start = path.charAt(0) == '/' ? 1 : 0;
        int count = 1;
        for (int i = start; i < len; i++) {
            if (path.charAt(i) == '/') {
                count++;
            }
        }
        int[] offs = new int[count];
        int idx = 0;
        offs[idx++] = start;
        for (int i = start; i < len; i++) {
            if (path.charAt(i) == '/') {
                offs[idx++] = i + 1;
            }
        }
        return offs;
    }

    private int pathLengthWithoutTrailingSlash() {
        int len = path.length();
        return (len > 1 && path.charAt(len - 1) == '/') ? len - 1 : len;
    }

    /**
     * Returns the name component at the given index using precomputed offsets.
     */
    private String componentAt(int[] offs, int index) {
        return path.substring(componentStart(offs, index), componentEnd(offs, index));
    }

    /**
     * @return the start index (inclusive) of the component at {@code index}
     */
    private int componentStart(int[] offs, int index) {
        return offs[index];
    }

    /**
     * @return the end index (exclusive) of the component at {@code index}
     */
    private int componentEnd(int[] offs, int index) {
        return (index + 1 < offs.length) ? offs[index + 1] - 1 : pathLengthWithoutTrailingSlash();
    }

    /**
     * Compares a component of this path with a component of another path
     * without allocating substrings.
     */
    private boolean componentEquals(int[] thisOffs, int thisIndex,
            ReadOnlyZipPath other, int[] otherOffs, int otherIndex) {
        int thisStart = componentStart(thisOffs, thisIndex);
        int thisEnd = componentEnd(thisOffs, thisIndex);
        int otherStart = other.componentStart(otherOffs, otherIndex);
        int otherEnd = other.componentEnd(otherOffs, otherIndex);
        int len = thisEnd - thisStart;
        if (len != otherEnd - otherStart) {
            return false;
        }
        return path.regionMatches(thisStart, other.path, otherStart, len);
    }

    /**
     * Casts the given path to {@link ReadOnlyZipPath} and verifies it belongs
     * to the same filesystem.
     *
     * @throws ProviderMismatchException if the path is from a different provider
     */
    private ReadOnlyZipPath requireSameFs(Path other) {
        Objects.requireNonNull(other);
        if (other instanceof ReadOnlyZipPath o) {
            return o;
        }
        throw new ProviderMismatchException("Expected ReadOnlyZipPath but got " + other.getClass().getName());
    }

    /**
     * Attempts to cast the given path to {@link ReadOnlyZipPath} from the same
     * filesystem.
     *
     * @return the cast path, or {@code null} if incompatible
     */
    private ReadOnlyZipPath toReadOnlyZipPath(Path other) {
        if (other instanceof ReadOnlyZipPath o) {
            if (o.fileSystem == this.fileSystem) {
                return o;
            }
        }
        return null;
    }
}
