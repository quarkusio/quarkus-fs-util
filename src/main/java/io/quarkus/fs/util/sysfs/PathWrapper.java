package io.quarkus.fs.util.sysfs;

import io.quarkus.fs.util.base.DelegatingPath;
import java.nio.file.FileSystem;
import java.nio.file.Path;

public class PathWrapper extends DelegatingPath {
    private final FileSystem fileSystem;

    /**
     *
     * @param delegate the Path to delegate to. May not be null.
     * @param fileSystem any calls to {@link #getFileSystem()} ()} will return this fileSystem instead of the delegates ones.
     *        May not be null.
     */
    public PathWrapper(Path delegate, FileSystem fileSystem) {
        super(delegate);
        this.fileSystem = fileSystem;
    }

    @Override
    public FileSystem getFileSystem() {
        return fileSystem;
    }
}
