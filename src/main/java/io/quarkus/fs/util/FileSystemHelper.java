package io.quarkus.fs.util;

import io.quarkus.fs.util.sysfs.ConfigurableFileSystemProviderWrapper;
import io.quarkus.fs.util.sysfs.FileSystemWrapper;
import io.quarkus.fs.util.sysfs.PathWrapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.AccessMode;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

public class FileSystemHelper {
    /**
     * @param path
     * @param env
     * @param classLoader
     * @return
     * @throws IOException
     */
    public static FileSystem openFS(Path path, Map<String, ?> env, ClassLoader classLoader) throws IOException {
        return FileSystems.newFileSystem(path, env, classLoader);
    }

    public static FileSystem openFS(URI uri, Map<String, ?> env, ClassLoader classLoader) throws IOException {
        return FileSystems.newFileSystem(uri, env, classLoader);
    }

    /**
     * Wraps the given path with a {@link io.quarkus.fs.util.sysfs.PathWrapper}, preventing file system writeability checks.
     *
     * @param path
     * @return
     */
    public static Path ignoreFileWriteability(Path path) {
        FileSystemProvider provider = new ConfigurableFileSystemProviderWrapper(path.getFileSystem().provider(),
                Set.of(AccessMode.WRITE));
        FileSystem fileSystem = new FileSystemWrapper(path.getFileSystem(), provider);
        return new PathWrapper(path, fileSystem);
    }
}
