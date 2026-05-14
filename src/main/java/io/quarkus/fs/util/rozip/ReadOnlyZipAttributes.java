package io.quarkus.fs.util.rozip;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

/**
 * Read-only {@link BasicFileAttributes} implementation backed by {@link ZipEntryInfo}.
 * <p>
 * ZIP archives do not track access or creation times separately, so all three
 * timestamps ({@link #lastModifiedTime()}, {@link #lastAccessTime()},
 * {@link #creationTime()}) return the same value derived from the entry's
 * DOS-format last-modified timestamp.
 */
final class ReadOnlyZipAttributes implements BasicFileAttributes {

    /**
     * Synthetic attributes for the root directory {@code "/"}, which has no
     * corresponding entry in the central directory.
     */
    static final ReadOnlyZipAttributes ROOT = new ReadOnlyZipAttributes(null);

    private final ZipEntryInfo entry;

    /**
     * @param entry the backing entry info, or {@code null} for the root directory
     */
    ReadOnlyZipAttributes(ZipEntryInfo entry) {
        this.entry = entry;
    }

    @Override
    public FileTime lastModifiedTime() {
        return entry != null
                ? FileTime.fromMillis(entry.lastModifiedTime())
                : FileTime.fromMillis(0L);
    }

    @Override
    public FileTime lastAccessTime() {
        return lastModifiedTime();
    }

    @Override
    public FileTime creationTime() {
        return lastModifiedTime();
    }

    @Override
    public boolean isRegularFile() {
        return entry != null && !entry.directory();
    }

    @Override
    public boolean isDirectory() {
        return entry == null || entry.directory();
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return entry != null ? entry.uncompressedSize() : 0L;
    }

    /**
     * @return {@code null}; ZIP entries have no meaningful file key
     */
    @Override
    public Object fileKey() {
        return null;
    }
}
