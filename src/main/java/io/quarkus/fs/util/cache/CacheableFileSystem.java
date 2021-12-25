package io.quarkus.fs.util.cache;

import io.quarkus.fs.util.base.DelegatingFileSystem;
import java.io.IOException;
import java.nio.file.FileSystem;

/**
 * Cacheable FileSystem implementation. This delegates most of its operations to another filesystem. Calling close() call has no
 * effect.
 * The delegate filesystem has to be threadsafe, i.e. multiple thread might potentially read and write to it at the same time.
 */
public class CacheableFileSystem extends DelegatingFileSystem {

    public CacheableFileSystem(FileSystem delegate) {
        super(delegate);
    }

    public FileSystem getDelegate() {
        return super.delegate;
    }

    @Override
    public void close() throws IOException {
        // do nothing, the cache will close this fs eventually
    }

}
