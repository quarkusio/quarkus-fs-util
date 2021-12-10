package io.quarkus.fs.util;

import java.nio.file.spi.FileSystemProvider;

public class FileSystemProviders {
    /**
     * FileSystemProvider which supports a "jar" scheme, e.g. for opening zip or jar files.
     */
    public static final FileSystemProvider ZIP_PROVIDER;
    static {
        FileSystemProvider foundZipProvider = null;
        for (FileSystemProvider installedProvider : FileSystemProvider.installedProviders()) {
            if (installedProvider.getScheme().equals("jar")) {
                foundZipProvider = installedProvider;
                break;
            }
        }
        ZIP_PROVIDER = foundZipProvider;
    }
}
