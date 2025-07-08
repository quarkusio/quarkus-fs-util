package io.quarkus.fs.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

class ZipUtilsTest {

    /**
     * Test that when an operation on a corrupt zip file fails, the resulting IOException
     * has the path details of the file/uri of the source file
     *
     * @throws Exception
     * @see <a href="https://github.com/quarkusio/quarkus/issues/4126"/>
     */
    @Test
    public void testCorruptZipException() throws Exception {
        final Path tmpFile = Files.createTempFile(null, ".jar");
        try {
            final URI uri = new URI("jar", tmpFile.toUri().toString(), null);
            try {
                ZipUtils.newFileSystem(uri, Collections.emptyMap());
                Assertions.fail("New filesystem creation was expected to fail for a non-zip file");
            } catch (IOException ioe) {
                // verify the exception message content
                if (!ioe.getMessage().contains(uri.toString())) {
                    throw ioe;
                }
            }

            try {
                ZipUtils.newFileSystem(tmpFile);
                Assertions.fail("New filesystem creation was expected to fail for a non-zip file");
            } catch (IOException ioe) {
                // verify the exception message content
                if (!ioe.getMessage().contains(tmpFile.toString())) {
                    throw ioe;
                }
            }
        } finally {
            Files.delete(tmpFile);
        }
    }

    /**
     * Test that the {@link ZipUtils#newZip(Path)} works as expected
     *
     * @throws Exception
     */
    @Test
    public void testNewZip() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");
        try {
            try (final FileSystem fs = ZipUtils.newZip(zipPath)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello");
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    /**
     * Test that the {@link ZipUtils#newZip(Path, Map)} works as expected
     *
     * @throws Exception
     */
    @Test
    public void testNewNonCompressedZip() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");
        try {
            try (final FileSystem fs = ZipUtils.newZip(zipPath, Map.of("compressionMethod", "STORED"))) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                Path helloFilePath = fs.getPath("hello.txt");
                assertFileExistsWithContent(helloFilePath, "hello");
            }

        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    public void testIllegalEnv() {
        assertThrows(IllegalArgumentException.class, () -> {
            final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
            final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");
            try {
                try (final FileSystem fs = ZipUtils.newZip(zipPath, Map.of("compressionMethod", "DUMMY"))) {
                    final Path someFileInZip = fs.getPath("hello.txt");
                    Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
                }
            } finally {
                Files.deleteIfExists(zipPath);
            }
        });
    }

    /**
     * Test that the {@link ZipUtils#newZip(Path)} works as expected when the path contains a question mark
     *
     * Windows does not support question marks in file names
     *
     * @throws Exception
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testNewZipWithQuestionMark() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputils?test-" + System.currentTimeMillis() + ".jar");
        try {
            try (final FileSystem fs = ZipUtils.newZip(zipPath)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello");
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    /**
     * Tests that the {@link ZipUtils#newZip(Path)} works correctly, and creates the zip file,
     * when the parent directories of the {@link Path} passed to it are not present.
     *
     * @throws Exception
     * @see <a href="https://github.com/quarkusio/quarkus/issues/5680"/>
     */
    @Test
    public void testNewZipForNonExistentParentDir() throws Exception {
        final Path tmpDir = Files.createTempDirectory(null);
        final Path nonExistentLevel1Dir = tmpDir.resolve("non-existent-level1");
        final Path nonExistentLevel2Dir = nonExistentLevel1Dir.resolve("non-existent-level2");
        final Path zipPath = Paths.get(nonExistentLevel2Dir.toString(), "ziputilstest-nonexistentdirs.jar");
        try {
            try (final FileSystem fs = ZipUtils.newZip(zipPath)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello");
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    public void testCreateNewReproducibleZipFileSystem() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");
        final Instant referenceTimestamp = Instant.parse("2010-04-09T10:15:30.00Z");

        try {
            try (final FileSystem fs = ZipUtils.createNewReproducibleZipFileSystem(zipPath, referenceTimestamp)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello", referenceTimestamp);
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    public void testCreateNewReproducibleZipFileSystemNullEntryTime() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");

        try {
            try (final FileSystem fs = ZipUtils.createNewReproducibleZipFileSystem(zipPath, null)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello");
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    public void testCreateNewReproducibleZipFileSystemNonCompressedZip() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");
        final Instant referenceTimestamp = Instant.parse("2010-04-09T10:15:30.00Z");
        try {
            try (final FileSystem fs = ZipUtils.createNewReproducibleZipFileSystem(zipPath,
                    Map.of("compressionMethod", "STORED"),
                    referenceTimestamp)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                Path helloFilePath = fs.getPath("hello.txt");
                assertFileExistsWithContent(helloFilePath, "hello", referenceTimestamp);
            }

        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    public void testCreateNewReproducibleZipFileSystemIllegalEnv() {
        assertThrows(IllegalArgumentException.class, () -> {
            final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
            final Path zipPath = Paths.get(tmpDir.toString(), "ziputilstest-" + System.currentTimeMillis() + ".jar");
            final Instant referenceTimestamp = Instant.parse("2010-04-09T10:15:30.00Z");
            try {
                try (final FileSystem fs = ZipUtils.createNewReproducibleZipFileSystem(zipPath,
                        Map.of("compressionMethod", "DUMMY"), referenceTimestamp)) {
                    final Path someFileInZip = fs.getPath("hello.txt");
                    Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
                }
            } finally {
                Files.deleteIfExists(zipPath);
            }
        });
    }

    /**
     * Windows does not support question marks in file names
     */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    public void testCreateNewReproducibleZipFileSystemWithQuestionMark() throws Exception {
        final Path tmpDir = Paths.get(System.getProperty("java.io.tmpdir"));
        final Path zipPath = Paths.get(tmpDir.toString(), "ziputils?test-" + System.currentTimeMillis() + ".jar");
        final Instant referenceTimestamp = Instant.parse("2010-04-09T10:15:30.00Z");
        try {
            try (final FileSystem fs = ZipUtils.createNewReproducibleZipFileSystem(zipPath, referenceTimestamp)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello", referenceTimestamp);
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    @Test
    public void testCreateNewReproducibleZipFileSystemForNonExistentParentDir() throws Exception {
        final Path tmpDir = Files.createTempDirectory(null);
        final Path nonExistentLevel1Dir = tmpDir.resolve("non-existent-level1");
        final Path nonExistentLevel2Dir = nonExistentLevel1Dir.resolve("non-existent-level2");
        final Path zipPath = Paths.get(nonExistentLevel2Dir.toString(), "ziputilstest-nonexistentdirs.jar");
        final Instant referenceTimestamp = Instant.parse("2010-04-09T10:15:30.00Z");
        try {
            try (final FileSystem fs = ZipUtils.createNewReproducibleZipFileSystem(zipPath, referenceTimestamp)) {
                final Path someFileInZip = fs.getPath("hello.txt");
                Files.write(someFileInZip, "hello".getBytes(StandardCharsets.UTF_8));
            }
            // now just verify that the content was actually written out
            try (final FileSystem fs = ZipUtils.newFileSystem(zipPath)) {
                assertFileExistsWithContent(fs.getPath("hello.txt"), "hello", referenceTimestamp);
            }
        } finally {
            Files.deleteIfExists(zipPath);
        }
    }

    private static void assertFileExistsWithContent(final Path path, final String content) throws IOException {
        assertFileExistsWithContent(path, content, null);
    }

    private static void assertFileExistsWithContent(final Path path, final String content, Instant referenceTimestamp)
            throws IOException {
        final String readContent = Files.readString(path);
        BasicFileAttributes attr = Files.readAttributes(path, BasicFileAttributes.class);

        assertEquals(content, readContent, "Unexpected content in " + path);

        if (referenceTimestamp != null) {
            assertEquals(FileTime.from(referenceTimestamp), attr.lastAccessTime(), "Unexpected lastAccessTime in " + path);
            assertEquals(FileTime.from(referenceTimestamp), attr.lastModifiedTime(), "Unexpected lastModifiedTime in " + path);
            assertEquals(FileTime.from(referenceTimestamp), attr.creationTime(), "Unexpected creationTime in " + path);
        }
    }
}
