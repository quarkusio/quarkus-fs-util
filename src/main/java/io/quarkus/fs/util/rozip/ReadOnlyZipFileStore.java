package io.quarkus.fs.util.rozip;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

/**
 * A read-only {@link FileStore} backed by a ZIP archive file.
 */
class ReadOnlyZipFileStore extends FileStore {

    private final Path zipPath;

    /**
     * @param zipPath path to the ZIP archive on the default filesystem
     */
    ReadOnlyZipFileStore(Path zipPath) {
        this.zipPath = zipPath;
    }

    /**
     * @return the string form of the archive path
     */
    @Override
    public String name() {
        return zipPath.toString();
    }

    /**
     * @return {@code "zip"}
     */
    @Override
    public String type() {
        return "zip";
    }

    /**
     * @return {@code true} always
     */
    @Override
    public boolean isReadOnly() {
        return true;
    }

    /**
     * @return the size of the ZIP archive file in bytes
     * @throws IOException if the file size cannot be read
     */
    @Override
    public long getTotalSpace() throws IOException {
        return Files.size(zipPath);
    }

    /**
     * @return {@code 0} always; the archive is read-only
     */
    @Override
    public long getUsableSpace() {
        return 0;
    }

    /**
     * @return {@code 0} always; the archive is read-only
     */
    @Override
    public long getUnallocatedSpace() {
        return 0;
    }

    /**
     * @param type the attribute view class to check
     * @return {@code true} if {@code type} is {@link BasicFileAttributeView}
     */
    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return type == BasicFileAttributeView.class;
    }

    /**
     * @param name the attribute view name to check
     * @return {@code true} if {@code name} is {@code "basic"}
     */
    @Override
    public boolean supportsFileAttributeView(String name) {
        return "basic".equals(name);
    }

    /**
     * @return {@code null} always; no file store attribute views are supported
     */
    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return null;
    }

    /**
     * @throws UnsupportedOperationException always; no store-level
     *         attributes are supported
     */
    @Override
    public Object getAttribute(String attribute) throws IOException {
        throw new UnsupportedOperationException("Attribute " + attribute + " not supported");
    }
}
