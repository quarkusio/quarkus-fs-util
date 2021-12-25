package io.quarkus.fs.util.cache;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

public class FileSystemCache implements Closeable {
    public static final FileSystemCache INSTANCE = new FileSystemCache();

    private final Map<String, CacheableFileSystem> fileSystems = new HashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    private FileSystemCache() {
    }

    /**
     * Constructs a new FileSystem to access the contents of a file as a file system.
     * This caches the created FileSystem instances, until {@link #close()} is called. Calling close on the FS itself will have
     * no effect
     *
     * @param fileSystemProvider
     * @param path
     * @param env
     * @return
     * @throws IOException
     */
    public FileSystem getOrCreateFileSystem(FileSystemProvider fileSystemProvider, Path path, Map<String, Object> env)
            throws IOException {

        String key = path.toString();

        CacheableFileSystem fileSystem = fileSystems.get(key);
        if (fileSystem == null) {
            ReentrantLock reentrantLock = locks.computeIfAbsent(key, (p) -> new ReentrantLock());
            try {
                reentrantLock.lock();
                fileSystem = fileSystems.get(key);
                if (fileSystem == null) {
                    fileSystem = new CacheableFileSystem(fileSystemProvider.newFileSystem(path, env));
                    fileSystems.put(key, fileSystem);
                }
            } finally {
                reentrantLock.unlock();
            }
        }

        return fileSystem;
    }

    /**
     * Close all cached FileSystem instances, and clear the cache.
     */
    @Override
    public void close() throws IOException {
        synchronized (fileSystems) {
            for (CacheableFileSystem value : fileSystems.values()) {
                value.getDelegate().close();
            }
            fileSystems.clear();
        }
    }
}
