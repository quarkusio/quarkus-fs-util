
package io.quarkus.fs.util;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.nio.file.spi.FileSystemProvider;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipError;

/**
 *
 * @author Alexey Loubyansky
 */
public class ZipUtils {

    private static final String JAR_URI_PREFIX = "jar:";
    private static final Map<String, Object> DEFAULT_OWNER_ENV = new HashMap<>();
    private static final Map<String, Object> CREATE_ENV = new HashMap<>();

    static {
        String user = System.getProperty("user.name");
        DEFAULT_OWNER_ENV.put("defaultOwner", user);
        DEFAULT_OWNER_ENV.put("defaultGroup", user);

        CREATE_ENV.putAll(DEFAULT_OWNER_ENV);
        CREATE_ENV.put("create", "true");
    }

    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        try {
            if (!Files.exists(targetDir)) {
                Files.createDirectories(targetDir);
            }
        } catch (FileAlreadyExistsException fae) {
            throw new IOException("Could not create directory '" + targetDir + "' as a file already exists with the same name");
        }
        try (FileSystem zipfs = newFileSystem(zipFile)) {
            for (Path zipRoot : zipfs.getRootDirectories()) {
                copyFromZip(zipRoot, targetDir);
            }
        } catch (IOException | ZipError ioe) {
            // TODO: (at a later date) Get rid of the ZipError catching (and instead only catch IOException)
            //  since it's a JDK bug which threw the undeclared ZipError instead of an IOException.
            //  Java 9 fixes it https://bugs.openjdk.java.net/browse/JDK-8062754

            throw new IOException("Could not unzip " + zipFile + " to target dir " + targetDir, ioe);
        }
    }

    public static URI toZipUri(Path zipFile) throws IOException {
        URI zipUri = zipFile.toUri();
        try {
            zipUri = new URL(JAR_URI_PREFIX + zipUri.getScheme() + "://" +
                    zipUri.getRawPath().replace("!/", "%21/") + "!/").toURI();
        } catch (URISyntaxException e) {
            throw new IOException("Failed to create a JAR URI for " + zipFile, e);
        }
        return zipUri;
    }

    public static void copyFromZip(Path source, Path target) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        final Path targetDir = target.resolve(source.relativize(dir).toString());
                        try {
                            Files.copy(dir, targetDir);
                        } catch (FileAlreadyExistsException e) {
                            if (!Files.isDirectory(targetDir))
                                throw e;
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.copy(file, target.resolve(source.relativize(file).toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    public static void zip(Path src, Path zipFile) throws IOException {
        try (FileSystem zipfs = newZip(zipFile)) {
            if (Files.isDirectory(src)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(src)) {
                    for (Path srcPath : stream) {
                        copyToZip(src, srcPath, zipfs);
                    }
                }
            } else {
                Files.copy(src, zipfs.getPath(src.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public static FileSystem newZip(Path zipFile) throws IOException {
        return newZip(zipFile, Collections.emptyMap());
    }

    /**
     * In this version, callers can set whatever parameters they like for the FileSystem.
     * Note that these values always take precedence over whatever the library itself sets.
     */
    public static FileSystem newZip(Path zipFile, Map<String, Object> env) throws IOException {
        Map<String, Object> effectiveEnv;
        if (Files.exists(zipFile)) {
            effectiveEnv = DEFAULT_OWNER_ENV;
        } else {
            effectiveEnv = CREATE_ENV;
            // explicitly create any parent dirs, since the ZipFileSystem only creates a new file
            // with "create" = "true", but doesn't create any parent dirs.

            // It's OK to not check the existence of the parent dir(s) first, since the API,
            // as per its contract doesn't throw any exception if the parent dir(s) already exist
            Files.createDirectories(zipFile.getParent());
        }
        if (env != null) {
            effectiveEnv = new HashMap<>(effectiveEnv); // we need to copy in order avoid polluting the static values
            effectiveEnv.putAll(env);
        }
        try {
            return FileSystemProviders.ZIP_PROVIDER.newFileSystem(toZipUri(zipFile), effectiveEnv);
        } catch (IOException ioe) {
            // include the URI for which the filesystem creation failed
            throw new IOException("Failed to create a new filesystem for " + zipFile, ioe);
        }
    }

    private static void copyToZip(Path srcRoot, Path srcPath, FileSystem zipfs) throws IOException {
        Files.walkFileTree(srcPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                            throws IOException {
                        final Path targetDir = zipfs.getPath(srcRoot.relativize(dir).toString());
                        try {
                            Files.copy(dir, targetDir);
                        } catch (FileAlreadyExistsException e) {
                            if (!Files.isDirectory(targetDir)) {
                                throw e;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                            throws IOException {
                        Files.copy(file, zipfs.getPath(srcRoot.relativize(file).toString()),
                                StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    /**
     * This call is not thread safe, a single of FileSystem can be created for the
     * provided uri until it is closed.
     *
     * @param uri The uri to the zip file.
     * @param env Env map.
     * @return A new FileSystem.
     * @throws IOException in case of a failure
     */
    public static FileSystem newFileSystem(URI uri, Map<String, Object> env) throws IOException {
        env = new HashMap<>(env);
        env.putAll(DEFAULT_OWNER_ENV);

        // If Multi threading required, logic should be added to wrap this fs
        // onto a fs that handles a reference counter and close the fs only when all thread are done
        // with it.
        try {
            return FileSystemProviders.ZIP_PROVIDER.newFileSystem(uri, env);
        } catch (FileSystemAlreadyExistsException e) {
            throw new IOException("fs already exists " + uri, e);
        } catch (IOException ioe) {
            // include the URI for which the filesystem creation failed
            throw new IOException("Failed to create a new filesystem for " + uri, ioe);
        }
    }

    /**
     * Constructs a new FileSystem to access the contents of a zip as a file system.
     *
     * @param path The zip file.
     * @return A new FileSystem instance
     * @throws IOException in case of a failure
     */
    public static FileSystem newFileSystem(Path path) throws IOException {
        return newFileSystem(path, Collections.emptyMap());
    }

    /**
     * Constructs a new FileSystem to access the contents of a zip as a file system.
     *
     * @param path The zip file.
     * @param env Property map to configure the constructed FileSystem.
     * @return A new FileSystem instance
     * @throws IOException in case of a failure
     */
    private static FileSystem newFileSystem(Path path, Map<String, Object> env) throws IOException {
        final Map<String, Object> tmp = new HashMap<>(DEFAULT_OWNER_ENV);
        tmp.putAll(env);
        env = tmp;

        try {
            path = FileSystemHelper.ignoreFileWriteability(path);
            return FileSystemProviders.ZIP_PROVIDER.newFileSystem(path, env);
        } catch (IOException ioe) {
            // include the path for which the filesystem creation failed
            throw new IOException("Failed to create a new filesystem for " + path, ioe);
        }
    }

    /**
     * This call is thread safe, a new FS is created for each invocation.
     *
     * @param path The zip file.
     * @param classLoader the classloader to locate the appropriate FileSystemProvider to open the path
     * @return A new FileSystem instance
     * @throws IOException in case of a failure
     * @deprecated Use {@link #newFileSystem(Path)}. Providing a classLoader makes no difference, since the
     *             ZipFileSystemProvider is part of the FileSystemProvider.installedProviders() list. No classloader needs to be
     *             searched for a custom FileSystemProvider.
     */
    @Deprecated
    public static FileSystem newFileSystem(final Path path, ClassLoader classLoader) throws IOException {
        return newFileSystem(path, Collections.emptyMap());
    }

    /**
     * This is a hack to get past the <a href="https://bugs.openjdk.java.net/browse/JDK-8232879">JDK-8232879</a>
     * issue which causes CRC errors when writing out data to (jar) files using ZipFileSystem.
     * <p>
     * Made into a no-op given the JDK issue has been fixed.
     *
     * @param original The original outputstream which will be wrapped into a new outputstream
     *        that delegates to this one.
     * @return
     * @deprecated Do not wrap the OutputStream, it is not needed anymore.
     */
    @Deprecated(forRemoval = true, since = "0.11.0")
    public static OutputStream wrapForJDK8232879(final OutputStream original) {
        return original;
    }

    /**
     * Create a new ZIP file, ensuring reproducibility by sorting the files before adding them and enforcing the timestamps.
     */
    public static void zipReproducibly(Path src, Path zipFile, Instant entryTime) throws IOException {
        try (FileSystem zipfs = createNewReproducibleZipFileSystem(zipFile, entryTime)) {
            if (Files.isDirectory(src)) {
                try (Stream<Path> stream = Files.walk(src)) {
                    stream.sorted() // sort the input paths to get a reproducible output
                            .forEach(srcPath -> {
                                final Path targetPath = zipfs.getPath(src.relativize(srcPath).toString());
                                try {
                                    if (Files.isDirectory(srcPath)) {
                                        try {
                                            Files.copy(srcPath, targetPath);
                                        } catch (FileAlreadyExistsException e) {
                                            if (!Files.isDirectory(targetPath)) {
                                                throw e;
                                            }
                                        }
                                    } else {
                                        Files.copy(srcPath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                                    }
                                } catch (IOException e) {
                                    throw new RuntimeException(
                                            String.format("Could not copy from %s into ZIP file %s", srcPath, zipFile));
                                }
                            });
                }
            } else {
                Files.copy(src, zipfs.getPath(src.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Create a new ZIP FileSystem, ensuring reproducibility by sorting the files before adding them and enforcing the
     * timestamps.
     */
    public static FileSystem createNewReproducibleZipFileSystem(Path zipFile, Instant entryTime) throws IOException {
        return createNewReproducibleZipFileSystem(zipFile, Collections.emptyMap(), entryTime);
    }

    /**
     * Create a new ZIP FileSystem, ensuring better reproducibility by enforcing entry timestamps for every entry.
     */
    public static FileSystem createNewReproducibleZipFileSystem(Path zipFile, Map<String, Object> env, Instant entryTime)
            throws IOException {
        if (Files.exists(zipFile)) {
            throw new IllegalArgumentException("Zip file " + zipFile + " already exists");
        }

        // explicitly create any parent dirs, since the ZipFileSystem only creates a new file
        // with "create" = "true", but doesn't create any parent dirs.
        // It's OK to not check the existence of the parent dir(s) first, since the API,
        // as per its contract doesn't throw any exception if the parent dir(s) already exist
        Files.createDirectories(zipFile.getParent());

        Map<String, Object> effectiveEnv = CREATE_ENV;
        if (env != null) {
            effectiveEnv = new HashMap<>(effectiveEnv); // we need to copy in order avoid polluting the static values
            effectiveEnv.putAll(env);
        }
        try {
            return new ReproducibleZipFileSystem(newFileSystem(toZipUri(zipFile), effectiveEnv), entryTime);
        } catch (IOException ioe) {
            // include the URI for which the filesystem creation failed
            throw new IOException("Failed to create a new filesystem for " + zipFile, ioe);
        }
    }

    /**
     * A wrapper delegating to another {@link FileSystem} instance that enforces {@link #entryTime} for every entry upon
     * {@link #close()}.
     */
    private static class ReproducibleZipFileSystem extends FileSystem {
        private final FileSystem delegate;
        private final FileTime entryTime;

        public ReproducibleZipFileSystem(FileSystem delegate, Instant entryTime) {
            this.delegate = delegate;
            this.entryTime = entryTime != null ? FileTime.fromMillis(entryTime.toEpochMilli()) : null;
        }

        @Override
        public int hashCode() {
            return delegate.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            return delegate.equals(obj);
        }

        @Override
        public FileSystemProvider provider() {
            return delegate.provider();
        }

        @Override
        public void close() throws IOException {
            if (entryTime == null) {
                delegate.close();
                return;
            }

            try {
                for (Path dir : delegate.getRootDirectories()) {
                    try (Stream<Path> stream = Files.walk(dir)) {
                        stream
                                .filter(path -> !"/".equals(path.toString())) // nothing to do for the root path
                                .forEach(path -> {
                                    try {
                                        Files.getFileAttributeView(path, BasicFileAttributeView.class)
                                                .setTimes(entryTime, entryTime, entryTime);
                                    } catch (IOException e) {
                                        throw new RuntimeException(String.format("Could not set time attributes on %s", path),
                                                e);
                                    }
                                });
                    }
                }
            } finally {
                delegate.close();
            }
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isReadOnly() {
            return delegate.isReadOnly();
        }

        @Override
        public String getSeparator() {
            return delegate.getSeparator();
        }

        @Override
        public Iterable<Path> getRootDirectories() {
            return delegate.getRootDirectories();
        }

        @Override
        public Iterable<FileStore> getFileStores() {
            return delegate.getFileStores();
        }

        @Override
        public String toString() {
            return delegate.toString();
        }

        @Override
        public Set<String> supportedFileAttributeViews() {
            return delegate.supportedFileAttributeViews();
        }

        @Override
        public Path getPath(String first, String... more) {
            return delegate.getPath(first, more);
        }

        @Override
        public PathMatcher getPathMatcher(String syntaxAndPattern) {
            return delegate.getPathMatcher(syntaxAndPattern);
        }

        @Override
        public UserPrincipalLookupService getUserPrincipalLookupService() {
            return delegate.getUserPrincipalLookupService();
        }

        @Override
        public WatchService newWatchService() throws IOException {
            return delegate.newWatchService();
        }
    }
}
