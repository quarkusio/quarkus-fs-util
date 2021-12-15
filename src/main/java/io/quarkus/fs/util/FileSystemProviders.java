package io.quarkus.fs.util;

import java.nio.file.spi.FileSystemProvider;

public class FileSystemProviders {
    /**
     * FileSystemProvider which supports a "jar" scheme, e.g. for opening zip or jar files.
     */
    public static final FileSystemProvider ZIP_PROVIDER;
    static {
        FileSystemProvider foundZipProvider = null;
        final ClassLoader ccl = Thread.currentThread().getContextClassLoader();
        try {
            // We are loading "installed" FS providers that are loaded from the system classloader anyway
            // To avoid potential ClassCastExceptions we are setting the context classloader to the system one
            Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
            for (FileSystemProvider installedProvider : FileSystemProvider.installedProviders()) {
                if (installedProvider.getScheme().equals("jar")) {
                    foundZipProvider = installedProvider;
                    break;
                }
            }
        } finally {
            Thread.currentThread().setContextClassLoader(ccl);
        }
        ZIP_PROVIDER = foundZipProvider;
    }
}
