package io.quarkus.fs.util.sysfs;

import io.quarkus.fs.util.base.DelegatingFileSystem;
import java.nio.file.FileSystem;
import java.nio.file.spi.FileSystemProvider;

public class FileSystemWrapper extends DelegatingFileSystem {
    private final FileSystemProvider fileSystemProvider;

    /**
     *
     * @param delegate the FileSystem to delegate to. May not be null.
     * @param fileSystemProvider any calls to {@link #provider()} will return this provider instead of the delegates ones.
     *        May not be null.
     */
    public FileSystemWrapper(FileSystem delegate, FileSystemProvider fileSystemProvider) {
        super(delegate);
        this.fileSystemProvider = fileSystemProvider;
    }

    @Override
    public FileSystemProvider provider() {
        return fileSystemProvider;
    }
}
