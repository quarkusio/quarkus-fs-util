package io.quarkus.fs.util;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;

public class FileSystemHelper {
    /**
     * Same as calling {@link FileSystems#newFileSystem(Path, ClassLoader)}. The env parameter is ignored when running on java
     * versions less than 13.
     *
     * @param path
     * @param env only used on java 13
     * @param classLoader
     * @return
     * @throws IOException
     */
    public static FileSystem openFS(Path path, Map<String, ?> env, ClassLoader classLoader) throws IOException {
        return FileSystems.newFileSystem(path, classLoader);
    }

    /**
     * Same as calling {@link FileSystems#newFileSystem(URI, Map, ClassLoader)}
     *
     * @param uri
     * @param env
     * @param classLoader
     * @return
     * @throws IOException
     */
    public static FileSystem openFS(URI uri, Map<String, ?> env, ClassLoader classLoader) throws IOException {
        return FileSystems.newFileSystem(uri, env, classLoader);
    }

    /**
     * Wraps the given path with a {@link io.quarkus.fs.util.sysfs.PathWrapper}, preventing file system writeability checks.
     * Only has an effect on jdk13 and up
     *
     * @param path
     * @return
     */
    public static Path ignoreFileWriteability(Path path) {
        // Do nothing. Some vendors' openjdk11 check that the paths filesystem equals the system on inside ZipFileSystemProvider.newFileSystem(Path, env), causing an UnsupportedOperationException.
        return path;
    }
}
