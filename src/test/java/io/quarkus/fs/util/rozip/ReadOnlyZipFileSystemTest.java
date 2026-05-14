package io.quarkus.fs.util.rozip;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.fs.util.ZipUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.NonWritableChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.ClosedFileSystemException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the read-only, non-interruptible ZIP filesystem.
 */
class ReadOnlyZipFileSystemTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void resetCache() {
        ReadOnlyZipFileSystem.CACHE_ENABLED = false;
    }

    // -- Entry reading --

    @Test
    void readTextEntry() throws IOException {
        Path zip = createZip("test.zip",
                entry("hello.txt", "hello world"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            String content = Files.readString(fs.getPath("/hello.txt"));
            assertEquals("hello world", content);
        }
    }

    @Test
    void readAllBytes() throws IOException {
        byte[] data = { 1, 2, 3, 4, 5 };
        Path zip = createZip("test.zip",
                entry("binary.dat", data));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            byte[] read = Files.readAllBytes(fs.getPath("/binary.dat"));
            assertArrayEquals(data, read);
        }
    }

    @Test
    void readViaInputStream() throws IOException {
        Path zip = createZip("test.zip",
                entry("data.txt", "test content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip);
                InputStream is = Files.newInputStream(fs.getPath("/data.txt"))) {
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertEquals("test content", content);
        }
    }

    @Test
    void readStoredEntry() throws IOException {
        Path zip = createZipWithMethod("stored.zip", ZipEntry.STORED,
                entry("file.txt", "stored content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            String content = Files.readString(fs.getPath("/file.txt"));
            assertEquals("stored content", content);
        }
    }

    @Test
    void readNonExistentEntryThrows() throws IOException {
        Path zip = createZip("test.zip",
                entry("exists.txt", "yes"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertThrows(NoSuchFileException.class,
                    () -> Files.readAllBytes(fs.getPath("/missing.txt")));
        }
    }

    // -- Directory traversal --

    @Test
    void walkDirectoryTree() throws IOException {
        Path zip = createZip("test.zip",
                entry("a/b/c.txt", "c"),
                entry("a/d.txt", "d"),
                entry("e.txt", "e"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip);
                Stream<Path> stream = Files.walk(fs.getPath("/"))) {
            List<String> paths = stream.map(Path::toString).sorted().collect(Collectors.toList());
            assertTrue(paths.contains("/"), "Should contain root");
            assertTrue(paths.contains("/a"), "Should contain /a");
            assertTrue(paths.contains("/a/b"), "Should contain /a/b");
            assertTrue(paths.contains("/a/b/c.txt"), "Should contain /a/b/c.txt");
            assertTrue(paths.contains("/a/d.txt"), "Should contain /a/d.txt");
            assertTrue(paths.contains("/e.txt"), "Should contain /e.txt");
        }
    }

    @Test
    void listDirectory() throws IOException {
        Path zip = createZip("test.zip",
                entry("dir/alpha.txt", "a"),
                entry("dir/beta.txt", "b"),
                entry("other.txt", "c"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip);
                DirectoryStream<Path> stream = Files.newDirectoryStream(fs.getPath("/dir"))) {
            List<String> names = new ArrayList<>();
            stream.forEach(p -> names.add(p.getFileName().toString()));
            Collections.sort(names);
            assertEquals(List.of("alpha.txt", "beta.txt"), names);
        }
    }

    @Test
    void listRoot() throws IOException {
        Path zip = createZip("test.zip",
                entry("a.txt", "a"),
                entry("b/c.txt", "c"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip);
                Stream<Path> stream = Files.list(fs.getPath("/"))) {
            List<String> names = stream
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
            assertEquals(List.of("a.txt", "b"), names);
        }
    }

    // -- Existence and type checks --

    @Test
    void existsAndTypeChecks() throws IOException {
        Path zip = createZip("test.zip",
                entry("file.txt", "content"),
                entry("dir/inner.txt", "inner"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertTrue(Files.exists(fs.getPath("/")));
            assertTrue(Files.exists(fs.getPath("/file.txt")));
            assertTrue(Files.exists(fs.getPath("/dir")));
            assertFalse(Files.exists(fs.getPath("/nonexistent")));

            assertTrue(Files.isDirectory(fs.getPath("/")));
            assertTrue(Files.isDirectory(fs.getPath("/dir")));
            assertFalse(Files.isDirectory(fs.getPath("/file.txt")));

            assertTrue(Files.isRegularFile(fs.getPath("/file.txt")));
            assertFalse(Files.isRegularFile(fs.getPath("/dir")));

            // trailing-slash paths must resolve the same as without
            assertTrue(Files.exists(fs.getPath("/dir/")));
            assertTrue(Files.isDirectory(fs.getPath("/dir/")));
            assertEquals("inner", Files.readString(fs.getPath("/dir/inner.txt")));
        }
    }

    // -- Attributes --

    @Test
    void readBasicAttributes() throws IOException {
        Path zip = createZip("test.zip",
                entry("file.txt", "content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            BasicFileAttributes attrs = Files.readAttributes(
                    fs.getPath("/file.txt"), BasicFileAttributes.class);
            assertTrue(attrs.isRegularFile());
            assertFalse(attrs.isDirectory());
            assertEquals(7, attrs.size()); // "content" = 7 bytes
            assertNotNull(attrs.lastModifiedTime());

            BasicFileAttributes rootAttrs = Files.readAttributes(
                    fs.getPath("/"), BasicFileAttributes.class);
            assertTrue(rootAttrs.isDirectory());
        }
    }

    @Test
    void implicitDirectoryAttributesHaveEpochZeroTimestamp() throws IOException {
        Path zip = createZip("test.zip",
                entry("a/b/file.txt", "content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            // "a" and "a/b" are implicit directories (no explicit central directory entry)
            BasicFileAttributes implicitAttrs = Files.readAttributes(
                    fs.getPath("/a"), BasicFileAttributes.class);
            assertTrue(implicitAttrs.isDirectory());
            assertEquals(FileTime.fromMillis(0L), implicitAttrs.lastModifiedTime(),
                    "Implicit directories should report epoch 0, consistent with the JDK's ZipFileSystem");
            assertEquals(0L, implicitAttrs.size());

            // The file itself should have a real timestamp
            BasicFileAttributes fileAttrs = Files.readAttributes(
                    fs.getPath("/a/b/file.txt"), BasicFileAttributes.class);
            assertTrue(fileAttrs.lastModifiedTime().toMillis() > 0,
                    "File entries should have a non-zero timestamp");
        }
    }

    @Test
    void newInputStreamOnImplicitDirectoryThrowsFileSystemException() throws IOException {
        Path zip = createZip("test.zip",
                entry("a/b/file.txt", "content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path implicitDir = fs.getPath("/a");
            assertTrue(Files.exists(implicitDir));
            assertTrue(Files.isDirectory(implicitDir));
            FileSystemException ex = assertThrows(FileSystemException.class,
                    () -> Files.newInputStream(implicitDir));
            assertTrue(ex.getReason().contains("directory"), ex.getReason());
        }
    }

    // -- Path operations --

    @Test
    void getPathJoinsComponents() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("a", "b", "c.txt");
            assertEquals("a/b/c.txt", p.toString());
        }
    }

    @Test
    void resolveAndRelativize() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path a = fs.getPath("/a");
            Path ab = a.resolve("b");
            assertEquals("/a/b", ab.toString());

            Path rel = fs.getPath("/a").relativize(fs.getPath("/a/b/c"));
            assertEquals("b/c", rel.toString());
        }
    }

    @Test
    void getFileNameAndParent() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("/a/b/c.txt");
            assertEquals("c.txt", p.getFileName().toString());
            assertEquals("/a/b", p.getParent().toString());
            assertEquals("/", p.getRoot().toString());

            assertNull(fs.getPath("/").getFileName());
            assertNull(fs.getPath("/").getParent());

            Path dirWithSlash = fs.getPath("/dir/");
            assertEquals("/", dirWithSlash.getParent().toString());
            assertEquals("dir", dirWithSlash.getFileName().toString());
        }
    }

    @Test
    void nameCountAndSubpath() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("/a/b/c");
            assertEquals(3, p.getNameCount());
            assertEquals("a", p.getName(0).toString());
            assertEquals("b", p.getName(1).toString());
            assertEquals("c", p.getName(2).toString());
            assertEquals("b/c", p.subpath(1, 3).toString());

            assertEquals(0, fs.getPath("/").getNameCount());
        }
    }

    @Test
    void normalize() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("/a/c", fs.getPath("/a/b/../c").normalize().toString());
            assertEquals("/a", fs.getPath("/a/./b/..").normalize().toString());
            assertEquals("/", fs.getPath("/a/..").normalize().toString());
        }
    }

    @Test
    void toAbsolutePath() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path relative = fs.getPath("a/b");
            assertFalse(relative.isAbsolute());
            Path absolute = relative.toAbsolutePath();
            assertTrue(absolute.isAbsolute());
            assertEquals("/a/b", absolute.toString());
        }
    }

    @Test
    void startsWithAndEndsWith() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("/a/b/c");
            assertTrue(p.startsWith("/a"));
            assertTrue(p.startsWith("/a/b"));
            assertFalse(p.startsWith("/b"));
            assertTrue(p.endsWith("c"));
            assertTrue(p.endsWith("b/c"));
            assertFalse(p.endsWith("a"));
        }
    }

    @Test
    void pathEquality() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p1 = fs.getPath("/a/b");
            Path p2 = fs.getPath("/a/b");
            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
            assertEquals(0, p1.compareTo(p2));
        }
    }

    @Test
    void pathIterator() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            List<String> names = new ArrayList<>();
            for (Path component : fs.getPath("/a/b/c")) {
                names.add(component.toString());
            }
            assertEquals(List.of("a", "b", "c"), names);
        }
    }

    // -- Read-only enforcement --

    @Test
    void isReadOnly() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertTrue(fs.isReadOnly());
        }
    }

    @Test
    void writeOperationsThrow() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertThrows(ReadOnlyFileSystemException.class,
                    () -> Files.createDirectory(fs.getPath("/newdir")));
            assertThrows(ReadOnlyFileSystemException.class,
                    () -> Files.delete(fs.getPath("/x.txt")));
            assertThrows(ReadOnlyFileSystemException.class,
                    () -> Files.write(fs.getPath("/out.txt"), new byte[] { 1 }));
        }
    }

    // -- Concurrent access --

    @Test
    void concurrentReads() throws Exception {
        Path zip = createZip("test.zip",
                entry("a.txt", "alpha"),
                entry("b.txt", "bravo"),
                entry("c.txt", "charlie"));

        int threadCount = 32;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        AtomicInteger errors = new AtomicInteger();

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        barrier.await();
                        String[] files = { "a.txt", "b.txt", "c.txt" };
                        String[] expected = { "alpha", "bravo", "charlie" };
                        for (int j = 0; j < 100; j++) {
                            int pick = (idx + j) % 3;
                            String content = Files.readString(fs.getPath("/" + files[pick]));
                            if (!expected[pick].equals(content)) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            executor.shutdown();
        }

        assertEquals(0, errors.get(), "No errors expected in concurrent reads");
    }

    // -- Interrupt resilience --

    @Test
    void interruptDoesNotBreakFileSystem() throws Exception {
        Path zip = createZip("test.zip",
                entry("data.txt", "important data"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            // Read normally first
            assertEquals("important data", Files.readString(fs.getPath("/data.txt")));

            // Set interrupt flag and read again
            Thread.currentThread().interrupt();
            try {
                assertEquals("important data", Files.readString(fs.getPath("/data.txt")));
            } finally {
                // Verify interrupt flag is preserved
                assertTrue(Thread.interrupted(), "Interrupt flag should be preserved");
            }

            // Read again after clearing interrupt — FS should still work
            assertEquals("important data", Files.readString(fs.getPath("/data.txt")));
        }
    }

    @Test
    void interruptedThreadDoesNotAffectOthers() throws Exception {
        Path zip = createZip("test.zip",
                entry("file.txt", "content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Thread interruptedThread = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    Files.readString(fs.getPath("/file.txt"));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                } finally {
                    Thread.interrupted(); // clear flag
                }
            });
            interruptedThread.start();
            interruptedThread.join();

            // Main thread should still be able to read
            assertEquals("content", Files.readString(fs.getPath("/file.txt")));
        }
    }

    // -- Close behavior --

    @Test
    void closeBehavior() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        FileSystem fs = ReadOnlyZipFileSystem.open(zip);
        assertTrue(fs.isOpen());

        fs.close();
        assertFalse(fs.isOpen());

        assertThrows(ClosedFileSystemException.class,
                () -> Files.readAllBytes(fs.getPath("/x.txt")));

        // Idempotent
        fs.close();
    }

    // -- Empty ZIP --

    @Test
    void emptyZip() throws IOException {
        Path zip = createZip("empty.zip");

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertTrue(Files.exists(fs.getPath("/")));
            assertTrue(Files.isDirectory(fs.getPath("/")));
            try (Stream<Path> stream = Files.walk(fs.getPath("/"))) {
                List<String> paths = stream.map(Path::toString).collect(Collectors.toList());
                assertEquals(List.of("/"), paths);
            }
        }
    }

    // -- Filesystem properties --

    @Test
    void filesystemProperties() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("/", fs.getSeparator());
            assertTrue(fs.isReadOnly());
            assertTrue(fs.supportedFileAttributeViews().contains("basic"));
        }
    }

    // -- Via ZipUtils factory --

    @Test
    void viaZipUtilsFactory() throws IOException {
        Path zip = createZip("test.zip",
                entry("hello.txt", "world"));

        try (FileSystem fs = ZipUtils.openReadOnly(zip)) {
            assertEquals("world", Files.readString(fs.getPath("/hello.txt")));
            assertTrue(fs.isReadOnly());
        }
    }

    // -- Multiple entries with nested directories --

    @Test
    void deeplyNestedEntries() throws IOException {
        Path zip = createZip("test.zip",
                entry("a/b/c/d/e.txt", "deep"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("deep", Files.readString(fs.getPath("/a/b/c/d/e.txt")));
            assertTrue(Files.isDirectory(fs.getPath("/a")));
            assertTrue(Files.isDirectory(fs.getPath("/a/b")));
            assertTrue(Files.isDirectory(fs.getPath("/a/b/c")));
            assertTrue(Files.isDirectory(fs.getPath("/a/b/c/d")));
        }
    }

    // -- readAttributes(String) map variant --

    @Test
    void readAttributesMapAll() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "content"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var map = Files.readAttributes(fs.getPath("/f.txt"), "basic:*");
            assertEquals(7L, map.get("size"));
            assertEquals(true, map.get("isRegularFile"));
            assertEquals(false, map.get("isDirectory"));
            assertNotNull(map.get("lastModifiedTime"));
        }
    }

    @Test
    void readAttributesMapSpecific() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "content"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var map = Files.readAttributes(fs.getPath("/f.txt"), "size,isDirectory");
            assertEquals(7L, map.get("size"));
            assertEquals(false, map.get("isDirectory"));
            assertEquals(2, map.size());
        }
    }

    @Test
    void readAttributesUnsupportedViewThrows() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertThrows(UnsupportedOperationException.class,
                    () -> Files.readAttributes(fs.getPath("/f.txt"), "posix:*"));
        }
    }

    // -- checkAccess --

    @Test
    void checkAccessRead() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            fs.provider().checkAccess(fs.getPath("/f.txt"));
        }
    }

    @Test
    void checkAccessWriteThrows() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertThrows(AccessDeniedException.class,
                    () -> fs.provider().checkAccess(fs.getPath("/f.txt"),
                            java.nio.file.AccessMode.WRITE));
        }
    }

    @Test
    void checkAccessNonExistentThrows() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertThrows(NoSuchFileException.class,
                    () -> fs.provider().checkAccess(fs.getPath("/missing.txt")));
        }
    }

    // -- SeekableByteChannel --

    @Test
    void byteChannelReadAndSeek() throws IOException {
        Path zip = createZip("test.zip", entry("data.txt", "abcdef"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip);
                SeekableByteChannel ch = Files.newByteChannel(fs.getPath("/data.txt"))) {
            assertEquals(6, ch.size());
            assertEquals(0, ch.position());

            ByteBuffer buf = ByteBuffer.allocate(3);
            ch.read(buf);
            buf.flip();
            assertEquals("abc", StandardCharsets.UTF_8.decode(buf).toString());
            assertEquals(3, ch.position());

            ch.position(1);
            buf.clear();
            ch.read(buf);
            buf.flip();
            assertEquals("bcd", StandardCharsets.UTF_8.decode(buf).toString());
        }
    }

    @Test
    void byteChannelWriteThrows() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip);
                SeekableByteChannel ch = Files.newByteChannel(fs.getPath("/x.txt"))) {
            assertThrows(NonWritableChannelException.class,
                    () -> ch.write(ByteBuffer.wrap(new byte[] { 1 })));
            assertThrows(NonWritableChannelException.class,
                    () -> ch.truncate(0));
        }
    }

    @Test
    void byteChannelClosedThrows() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            SeekableByteChannel ch = Files.newByteChannel(fs.getPath("/x.txt"));
            ch.close();
            assertFalse(ch.isOpen());
            assertThrows(java.nio.channels.ClosedChannelException.class,
                    () -> ch.read(ByteBuffer.allocate(1)));
        }
    }

    // -- PathMatcher / glob --

    @Test
    void globMatchesSingleStar() throws IOException {
        Path zip = createZip("test.zip",
                entry("Foo.class", "x"),
                entry("Bar.txt", "y"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:*.class");
            assertTrue(matcher.matches(fs.getPath("Foo.class")));
            assertFalse(matcher.matches(fs.getPath("Bar.txt")));
        }
    }

    @Test
    void globMatchesDoubleStar() throws IOException {
        Path zip = createZip("test.zip",
                entry("a/b/c.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:**/*.txt");
            assertTrue(matcher.matches(fs.getPath("a/b/c.txt")));
            assertFalse(matcher.matches(fs.getPath("a/b/c.jar")));
        }
    }

    @Test
    void globDoubleStarRequiresSeparator() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:a/**/b");
            assertTrue(matcher.matches(fs.getPath("a/b")));
            assertTrue(matcher.matches(fs.getPath("a/x/b")));
            assertTrue(matcher.matches(fs.getPath("a/x/y/b")));
            assertFalse(matcher.matches(fs.getPath("a/xb")));
        }
    }

    @Test
    void globLeadingDoubleStarMatchesAnyDepth() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:**/Foo.class");
            assertTrue(matcher.matches(fs.getPath("Foo.class")));
            assertTrue(matcher.matches(fs.getPath("com/Foo.class")));
            assertTrue(matcher.matches(fs.getPath("com/example/Foo.class")));
            assertFalse(matcher.matches(fs.getPath("Foo.txt")));
            assertFalse(matcher.matches(fs.getPath("com/Bar.class")));
        }
    }

    @Test
    void globStandaloneDoubleStarMatchesAllButNotEmpty() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:**");
            assertTrue(matcher.matches(fs.getPath("a.txt")));
            assertTrue(matcher.matches(fs.getPath("a/b/c.txt")));
            assertFalse(matcher.matches(fs.getPath("")));
        }
    }

    @Test
    void globTrailingDoubleStarMatchesDescendants() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:a/**");
            assertTrue(matcher.matches(fs.getPath("a/b")));
            assertTrue(matcher.matches(fs.getPath("a/b/c")));
            assertFalse(matcher.matches(fs.getPath("a/")));
        }
    }

    @Test
    void globMatchesBraceAlternation() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:*.{txt,xml}");
            assertTrue(matcher.matches(fs.getPath("file.txt")));
            assertTrue(matcher.matches(fs.getPath("file.xml")));
            assertFalse(matcher.matches(fs.getPath("file.jar")));
        }
    }

    @Test
    void globMatchesCharacterClass() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:[abc].txt");
            assertTrue(matcher.matches(fs.getPath("a.txt")));
            assertFalse(matcher.matches(fs.getPath("d.txt")));
        }
    }

    @Test
    void globMatchesNegatedCharacterClass() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:[!abc].txt");
            assertFalse(matcher.matches(fs.getPath("a.txt")));
            assertTrue(matcher.matches(fs.getPath("d.txt")));
        }
    }

    @Test
    void globMatchesNestedBraces() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:*.{txt,{xml,json}}");
            assertTrue(matcher.matches(fs.getPath("file.txt")));
            assertTrue(matcher.matches(fs.getPath("file.xml")));
            assertTrue(matcher.matches(fs.getPath("file.json")));
            assertFalse(matcher.matches(fs.getPath("file.jar")));
        }
    }

    @Test
    void globMatchesEscapedCharacters() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:file\\*.txt");
            assertTrue(matcher.matches(fs.getPath("file*.txt")));
            assertFalse(matcher.matches(fs.getPath("fileXYZ.txt")));
        }
    }

    @Test
    void globMatchesQuestionMark() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("glob:?.txt");
            assertTrue(matcher.matches(fs.getPath("a.txt")));
            assertFalse(matcher.matches(fs.getPath("ab.txt")));
            assertFalse(matcher.matches(fs.getPath("/.txt")));
        }
    }

    @Test
    void regexMatcher() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            var matcher = fs.getPathMatcher("regex:.*\\.class");
            assertTrue(matcher.matches(fs.getPath("Foo.class")));
            assertFalse(matcher.matches(fs.getPath("Foo.txt")));
        }
    }

    @Test
    void invalidPathMatcherSyntaxThrows() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertThrows(IllegalArgumentException.class,
                    () -> fs.getPathMatcher("nocolon"));
            assertThrows(UnsupportedOperationException.class,
                    () -> fs.getPathMatcher("unknown:pattern"));
        }
    }

    // -- Error cases --

    @Test
    void openNonZipFileThrows() throws IOException {
        Path notZip = tempDir.resolve("not-a-zip.txt");
        Files.writeString(notZip, "this is not a zip file");
        assertThrows(IOException.class, () -> ReadOnlyZipFileSystem.open(notZip));
    }

    @Test
    void openTruncatedZipThrows() throws IOException {
        Path zip = createZip("good.zip", entry("f.txt", "data"));
        byte[] bytes = Files.readAllBytes(zip);
        Path truncated = tempDir.resolve("truncated.zip");
        Files.write(truncated, java.util.Arrays.copyOf(bytes, 10));
        assertThrows(IOException.class, () -> ReadOnlyZipFileSystem.open(truncated));
    }

    // -- Multiple simultaneous filesystems --

    @Test
    void multipleFilesystemsIsolated() throws IOException {
        Path zip1 = createZip("one.zip", entry("a.txt", "alpha"));
        Path zip2 = createZip("two.zip", entry("b.txt", "bravo"));

        try (FileSystem fs1 = ReadOnlyZipFileSystem.open(zip1);
                FileSystem fs2 = ReadOnlyZipFileSystem.open(zip2)) {
            assertEquals("alpha", Files.readString(fs1.getPath("/a.txt")));
            assertEquals("bravo", Files.readString(fs2.getPath("/b.txt")));
            assertFalse(Files.exists(fs1.getPath("/b.txt")));
            assertFalse(Files.exists(fs2.getPath("/a.txt")));
        }
    }

    // -- CRC-32 verification --

    @Test
    void corruptedEntryDetected() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "hello"));
        byte[] raw = Files.readAllBytes(zip);
        // corrupt a byte near the end (inside the compressed data region)
        for (int i = raw.length - 30; i < raw.length - 22; i++) {
            raw[i] = (byte) (raw[i] ^ 0xFF);
        }
        Path corrupt = tempDir.resolve("corrupt.zip");
        Files.write(corrupt, raw);
        // parsing may fail, or reading the entry should fail with CRC/decompression error
        assertThrows(IOException.class, () -> {
            try (FileSystem fs = ReadOnlyZipFileSystem.open(corrupt)) {
                Files.readAllBytes(fs.getPath("/f.txt"));
            }
        });
    }

    @Test
    void unsupportedCompressionMethodThrows() throws IOException {
        Path zip = createZip("test.zip", entry("f.txt", "hello"));
        byte[] raw = Files.readAllBytes(zip);
        int cdOff = findCentralDirectoryOffset(raw);
        // patch compression method (offset 10 in CEN header) to 9 (deflate64)
        raw[cdOff + 10] = 9;
        raw[cdOff + 11] = 0;
        Path patched = tempDir.resolve("unsupported-method.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            IOException ex = assertThrows(IOException.class,
                    () -> Files.readAllBytes(fs.getPath("/f.txt")));
            assertTrue(ex.getMessage().contains("Unsupported compression method"), ex.getMessage());
        }
    }

    // -- Zip bomb protection --

    @Test
    void oversizedEntryRejected() throws IOException {
        Path zip = createZip("test.zip", entry("small.txt", "ok"));
        byte[] raw = Files.readAllBytes(zip);
        patchCentralDirectoryUncompressedSize(raw, ReadOnlyZipFileSystem.MAX_ENTRY_SIZE + 1);
        Path patched = tempDir.resolve("oversized.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            IOException ex = assertThrows(IOException.class,
                    () -> Files.readAllBytes(fs.getPath("/small.txt")));
            assertTrue(ex.getMessage().contains("Entry too large"), ex.getMessage());
        }
    }

    @Test
    void suspiciousCompressionRatioRejected() throws IOException {
        Path zip = createZip("test.zip", entry("small.txt", "ok"));
        byte[] raw = Files.readAllBytes(zip);
        // Set uncompressed size to (compressed size * 1001) to exceed the ratio limit
        long compressedSize = findCentralDirectoryCompressedSize(raw);
        long fakeUncompressed = compressedSize * 1001;
        patchCentralDirectoryUncompressedSize(raw, fakeUncompressed);
        Path patched = tempDir.resolve("ratio.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            IOException ex = assertThrows(IOException.class,
                    () -> Files.readAllBytes(fs.getPath("/small.txt")));
            assertTrue(ex.getMessage().contains("Suspicious compression ratio"), ex.getMessage());
        }
    }

    @Test
    void deflatedEmptyEntryWithZeroUncompressedSize() throws IOException {
        Path zip = createZip("test.zip", entry("empty.txt", ""));
        byte[] raw = Files.readAllBytes(zip);
        patchCentralDirectoryUncompressedSize(raw, 0);
        Path patched = tempDir.resolve("empty-zero-size.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            assertEquals("", Files.readString(fs.getPath("/empty.txt")));
        }
    }

    @Test
    void deflatedEntryWithZeroUncompressedSizeInCentralDirectory() throws IOException {
        Path zip = createZip("test.zip", entry("hello.txt", "hello world"));
        byte[] raw = Files.readAllBytes(zip);
        patchCentralDirectoryUncompressedSize(raw, 0);
        Path patched = tempDir.resolve("zero-size.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            assertEquals("hello world", Files.readString(fs.getPath("/hello.txt")));
        }
    }

    @Test
    void inflateDynamicUsedWhenAllSizeSourcesAreZero() throws IOException {
        // Non-empty content with non-zero CRC: the crc32==0 shortcut won't apply.
        // Zero out uncompressed size in both headers and clear bit 3 so the data
        // descriptor is not consulted — inflate must fall back to inflateDynamic.
        byte[] content = new byte[10_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i % 127 + 1);
        }
        Path zip = createZip("test.zip", entry("big.bin", content));
        byte[] raw = Files.readAllBytes(zip);
        patchCentralDirectoryUncompressedSize(raw, 0);
        patchLocalHeaderUncompressedSize(raw, 0);
        clearLocalHeaderDataDescriptorFlag(raw);
        Path patched = tempDir.resolve("dynamic-inflate.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            assertArrayEquals(content, Files.readAllBytes(fs.getPath("/big.bin")));
        }
    }

    @Test
    void dataDescriptorUncompressedSizeValidatedAgainstMaxEntrySize() throws IOException {
        byte[] content = "small payload".getBytes(StandardCharsets.UTF_8);
        byte[] raw = buildZipWithDataDescriptor("bomb.txt", content);

        // Patch the data descriptor's uncompressed size to exceed MAX_ENTRY_SIZE.
        // The data descriptor (with signature) starts right after compressed data.
        // Local header: 30 + name length. Then compressed data, then DD.
        int nameLen = "bomb.txt".length();
        int localHeaderEnd = 30 + nameLen;
        // Find compressed data length from the DD itself (at DD offset + 8)
        int ddOffset = -1;
        for (int i = localHeaderEnd; i < raw.length - 16; i++) {
            if (readLeInt32(raw, i) == 0x08074b50) {
                ddOffset = i;
                break;
            }
        }
        assertTrue(ddOffset > 0, "Data descriptor not found");
        // Patch uncompressed size in DD (offset 12 from DD start) to MAX_ENTRY_SIZE + 1
        writeLeUint32(raw, ddOffset + 12, ReadOnlyZipFileSystem.MAX_ENTRY_SIZE + 1);

        Path zip = tempDir.resolve("dd-bomb.zip");
        Files.write(zip, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            IOException ex = assertThrows(IOException.class,
                    () -> Files.readAllBytes(fs.getPath("/bomb.txt")));
            assertTrue(ex.getMessage().contains("Entry too large"), ex.getMessage());
        }
    }

    @Test
    void compressedSizeValidatedBeforeAllocation() throws IOException {
        Path zip = createZip("test.zip", entry("small.txt", "ok"));
        byte[] raw = Files.readAllBytes(zip);
        // Patch compressed size in CD to just over MAX_ENTRY_SIZE
        int cdOff = findCentralDirectoryOffset(raw);
        writeLeUint32(raw, cdOff + 20, ReadOnlyZipFileSystem.MAX_ENTRY_SIZE + 1);
        Path patched = tempDir.resolve("big-compressed.zip");
        Files.write(patched, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(patched)) {
            IOException ex = assertThrows(IOException.class,
                    () -> Files.readAllBytes(fs.getPath("/small.txt")));
            assertTrue(ex.getMessage().contains("Entry too large"), ex.getMessage());
        }
    }

    @Test
    void zip64ExtraFieldBoundedByOwnDataSize() throws IOException {
        // Build a ZIP64 archive where the ZIP64 extra field has dataSize=8
        // (only uncompressed size), followed by a dummy extra field whose bytes
        // should NOT be misread as compressedSize.
        byte[] data = "test".getBytes(StandardCharsets.UTF_8);
        byte[] nameBytes = "entry.txt".getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        long crcValue = crc.getValue();

        // ZIP64 extra: tag(2) + dataSize(2) + uncompressedSize(8) = 12 bytes, dataSize=8
        ByteBuffer zip64Extra = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        zip64Extra.putShort((short) 0x0001);
        zip64Extra.putShort((short) 8); // only stores uncompressed size
        zip64Extra.putLong(data.length);

        // Dummy extra field with garbage data that would produce a wrong compressedSize
        ByteBuffer dummyExtra = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        dummyExtra.putShort((short) 0x9999); // unknown tag
        dummyExtra.putShort((short) 8);
        dummyExtra.putLong(0xDEADBEEFCAFEL); // garbage

        byte[] combinedExtra = new byte[24];
        System.arraycopy(zip64Extra.array(), 0, combinedExtra, 0, 12);
        System.arraycopy(dummyExtra.array(), 0, combinedExtra, 12, 12);

        // Verify that readZip64Extra with short dataSize does NOT read past the ZIP64 field.
        // compressedSize should remain at 0xFFFFFFFF (unresolved) since ZIP64 field is too short.
        long[] result = ZipCentralDirectory.readZip64Extra(
                combinedExtra, 0, combinedExtra.length,
                0xFFFFFFFFL, 0xFFFFFFFFL, 0);
        assertEquals(data.length, result[0], "uncompressedSize should be read from ZIP64 field");
        assertEquals(0xFFFFFFFFL, result[1],
                "compressedSize should remain sentinel when ZIP64 field is too short");
    }

    @Test
    void truncatedCentralDirectoryEntryRejected() throws IOException {
        Path zip = createZip("test.zip", entry("hello.txt", "hello"));
        byte[] raw = Files.readAllBytes(zip);
        int cdOff = findCentralDirectoryOffset(raw);

        // Patch the name length in the CD entry to extend past the CD buffer
        int cdSize = raw.length - 22 - cdOff; // approximate CD size
        int nameLen = cdSize + 10; // name extends well past buffer
        raw[cdOff + 28] = (byte) (nameLen & 0xFF);
        raw[cdOff + 29] = (byte) ((nameLen >> 8) & 0xFF);

        Path patched = tempDir.resolve("truncated-cd.zip");
        Files.write(patched, raw);

        IOException ex = assertThrows(IOException.class,
                () -> ReadOnlyZipFileSystem.open(patched));
        assertTrue(ex.getMessage().contains("Truncated"), ex.getMessage());
    }

    @Test
    void deflatedEntryWithDataDescriptorProvidesUncompressedSize() throws IOException {
        byte[] content = "data descriptor entry".getBytes(StandardCharsets.UTF_8);
        byte[] raw = buildZipWithDataDescriptor("dd.txt", content);
        Path zip = tempDir.resolve("data-descriptor.zip");
        Files.write(zip, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("data descriptor entry", Files.readString(fs.getPath("/dd.txt")));
        }
    }

    @Test
    void zip64EntryWithDataDescriptorProvidesUncompressedSize() throws IOException {
        byte[] content = "zip64 data descriptor entry".getBytes(StandardCharsets.UTF_8);
        byte[] raw = buildZip64WithDataDescriptor("dd64.txt", content);
        Path zip = tempDir.resolve("zip64-data-descriptor.zip");
        Files.write(zip, raw);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("zip64 data descriptor entry", Files.readString(fs.getPath("/dd64.txt")));
        }
    }

    // -- Non-ASCII (UTF-8) entry names --

    @Test
    void readEntryWithUnicodeNames() throws IOException {
        Path zip = createZip("unicode.zip",
                entry("éàü.txt", "accented"),
                entry("日本語/テスト.txt", "japanese"),
                entry("中文.txt", "chinese"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("accented", Files.readString(fs.getPath("/éàü.txt")));
            assertEquals("japanese", Files.readString(fs.getPath("/日本語/テスト.txt")));
            assertEquals("chinese", Files.readString(fs.getPath("/中文.txt")));
            assertTrue(Files.isDirectory(fs.getPath("/日本語")));
        }
    }

    @Test
    void toUriEncodesSpecialCharacters() throws IOException {
        Path zip = createZip("uri.zip",
                entry("with space.txt", "s"),
                entry("file#1.txt", "h"),
                entry("café.txt", "c"),
                entry("q?mark.txt", "q"),
                entry("pct%val.txt", "p"),
                entry("a&b=c.txt", "a"),
                entry("dir/nested file.txt", "n"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            java.net.URI spaceUri = fs.getPath("/with space.txt").toUri();
            assertTrue(spaceUri.toString().contains("with%20space.txt"),
                    "Space should be percent-encoded: " + spaceUri);

            java.net.URI hashUri = fs.getPath("/file#1.txt").toUri();
            assertTrue(hashUri.toString().contains("file%231.txt"),
                    "Hash should be percent-encoded: " + hashUri);

            java.net.URI cafeUri = fs.getPath("/café.txt").toUri();
            assertNotNull(cafeUri);
            assertTrue(cafeUri.toString().contains("café.txt"),
                    "Non-ASCII is valid in IRIs: " + cafeUri);

            java.net.URI questionUri = fs.getPath("/q?mark.txt").toUri();
            assertTrue(questionUri.toString().contains("q%3Fmark.txt"),
                    "Question mark should be percent-encoded: " + questionUri);

            java.net.URI pctUri = fs.getPath("/pct%val.txt").toUri();
            assertTrue(pctUri.toString().contains("pct%25val.txt"),
                    "Percent sign should be percent-encoded: " + pctUri);

            java.net.URI ampUri = fs.getPath("/a&b=c.txt").toUri();
            assertTrue(ampUri.toString().contains("a&b=c.txt"),
                    "Ampersand and equals are valid URI chars: " + ampUri);

            java.net.URI nestedUri = fs.getPath("/dir/nested file.txt").toUri();
            assertTrue(nestedUri.toString().contains("dir/nested%20file.txt"),
                    "Nested path should preserve slashes and encode spaces: " + nestedUri);
        }
    }

    @Test
    void toUriProducesValidJarScheme() throws IOException {
        Path zip = createZip("uri.zip", entry("a.txt", "a"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            java.net.URI uri = fs.getPath("/a.txt").toUri();
            assertTrue(uri.toString().startsWith("jar:file:"),
                    "URI should start with jar:file: " + uri);
            assertTrue(uri.toString().contains("!/a.txt"),
                    "URI should contain !/ separator: " + uri);
        }
    }

    // -- isSameFile --

    @Test
    void isSameFileSamePath() throws IOException {
        Path zip = createZip("test.zip", entry("a.txt", "a"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("/a.txt");
            assertTrue(fs.provider().isSameFile(p, p));
        }
    }

    @Test
    void isSameFileEqualPaths() throws IOException {
        Path zip = createZip("test.zip", entry("a.txt", "a"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p1 = fs.getPath("/a.txt");
            Path p2 = fs.getPath("/a.txt");
            assertTrue(fs.provider().isSameFile(p1, p2));
        }
    }

    @Test
    void isSameFileNormalizedPaths() throws IOException {
        Path zip = createZip("test.zip", entry("a/b.txt", "b"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p1 = fs.getPath("/a/b.txt");
            Path p2 = fs.getPath("/a/c/../b.txt");
            assertTrue(fs.provider().isSameFile(p1, p2));
        }
    }

    @Test
    void isSameFileDifferentArchives() throws IOException {
        Path zip1 = createZip("one.zip", entry("a.txt", "a"));
        Path zip2 = createZip("two.zip", entry("a.txt", "a"));
        try (FileSystem fs1 = ReadOnlyZipFileSystem.open(zip1);
                FileSystem fs2 = ReadOnlyZipFileSystem.open(zip2)) {
            assertFalse(fs1.provider().isSameFile(fs1.getPath("/a.txt"), fs2.getPath("/a.txt")));
        }
    }

    @Test
    void isSameFileSameArchiveDifferentInstances() throws IOException {
        Path zip = createZip("test.zip", entry("a.txt", "a"));
        try (FileSystem fs1 = ReadOnlyZipFileSystem.open(zip);
                FileSystem fs2 = ReadOnlyZipFileSystem.open(zip)) {
            assertTrue(fs1.provider().isSameFile(fs1.getPath("/a.txt"), fs2.getPath("/a.txt")));
            assertTrue(fs1.provider().isSameFile(fs1.getPath("/a.txt"), fs2.getPath("/b/../a.txt")));
            assertFalse(fs1 == fs2, "Filesystem instances should be distinct");
        }
    }

    @Test
    void isSameFileDifferentPaths() throws IOException {
        Path zip = createZip("test.zip", entry("a.txt", "a"), entry("b.txt", "b"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertFalse(fs.provider().isSameFile(fs.getPath("/a.txt"), fs.getPath("/b.txt")));
        }
    }

    // -- ZIP64 --

    @Test
    void readZip64Archive() throws IOException {
        byte[] content = "zip64 content".getBytes(StandardCharsets.UTF_8);
        Path zip64 = tempDir.resolve("zip64.zip");
        Files.write(zip64, buildZip64Archive("hello.txt", content));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip64)) {
            assertEquals("zip64 content", Files.readString(fs.getPath("/hello.txt")));
            assertTrue(Files.isRegularFile(fs.getPath("/hello.txt")));
            BasicFileAttributes attrs = Files.readAttributes(
                    fs.getPath("/hello.txt"), BasicFileAttributes.class);
            assertEquals(content.length, attrs.size());
        }
    }

    // -- Extended timestamps --

    @Test
    void readsUnixExtendedTimestamp() throws IOException {
        // Odd second — DOS time has 2-second granularity, so second-level
        // precision proves the Unix extended timestamp (0x5455) was read.
        Instant timestamp = Instant.parse("2024-06-15T10:30:45Z");

        Path zip = tempDir.resolve("timestamps.zip");
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            ZipEntry ze = new ZipEntry("file.txt");
            ze.setLastModifiedTime(FileTime.from(timestamp));
            zos.putNextEntry(ze);
            zos.write("content".getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            BasicFileAttributes attrs = Files.readAttributes(
                    fs.getPath("/file.txt"), BasicFileAttributes.class);
            long actualEpochSecond = attrs.lastModifiedTime().toInstant().getEpochSecond();
            assertEquals(timestamp.getEpochSecond(), actualEpochSecond,
                    "Extended timestamp should preserve second-level precision");
        }
    }

    @Test
    void readsNtfsTimestamp() throws IOException {
        // Build a ZIP with an NTFS extra field (tag 0x000a) containing a known timestamp
        Instant expected = Instant.parse("2024-03-20T14:30:00Z");
        long expectedMillis = expected.toEpochMilli();
        // Windows FILETIME: 100-nanosecond intervals since 1601-01-01
        long winTime = (expectedMillis + 11_644_473_600_000L) * 10_000L;

        byte[] content = "ntfs".getBytes(StandardCharsets.UTF_8);
        Path zip = tempDir.resolve("ntfs.zip");
        Files.write(zip, buildZipWithNtfsTimestamp("file.txt", content, winTime));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            BasicFileAttributes attrs = Files.readAttributes(
                    fs.getPath("/file.txt"), BasicFileAttributes.class);
            assertEquals(expectedMillis, attrs.lastModifiedTime().toMillis(),
                    "NTFS timestamp should be read from extra field");
        }
    }

    // -- Reading a directory entry --

    @Test
    void readImplicitDirectoryEntryThrows() throws IOException {
        Path zip = createZip("test.zip",
                entry("dir/file.txt", "content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            FileSystemException ex = assertThrows(FileSystemException.class,
                    () -> Files.readAllBytes(fs.getPath("/dir")));
            assertTrue(ex.getReason().contains("directory"), ex.getReason());
        }
    }

    @Test
    void readExplicitDirectoryEntryThrows() throws IOException {
        Path zip = createZip("test.zip",
                entry("dir/", new byte[0]),
                entry("dir/file.txt", "content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            FileSystemException ex = assertThrows(FileSystemException.class,
                    () -> Files.readAllBytes(fs.getPath("/dir")));
            assertTrue(ex.getReason().contains("directory"), ex.getReason());
        }
    }

    // -- toRealPath --

    @Test
    void toRealPathNormalizesAndAbsolutes() throws IOException {
        Path zip = createZip("test.zip", entry("a/b.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("a/./c/../b.txt");
            Path real = p.toRealPath();
            assertTrue(real.isAbsolute());
            assertEquals("/a/b.txt", real.toString());
        }
    }

    // -- Lookup with unnormalized paths --

    @Test
    void lookupWithDotDotSegments() throws IOException {
        Path zip = createZip("test.zip",
                entry("a/b.txt", "content"),
                entry("a/c/d.txt", "deep"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("content", Files.readString(fs.getPath("/a/c/../b.txt")));
            assertEquals("deep", Files.readString(fs.getPath("/a/c/./d.txt")));
            assertTrue(Files.exists(fs.getPath("/a/c/../c/d.txt")));
            assertTrue(Files.isDirectory(fs.getPath("/a/c/..")));
        }
    }

    // -- resolveSibling --

    @Test
    void resolveSiblingPath() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("/a/b.txt");
            Path sibling = p.resolveSibling(fs.getPath("c.txt"));
            assertEquals("/a/c.txt", sibling.toString());
        }
    }

    @Test
    void resolveSiblingString() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path p = fs.getPath("/a/b.txt");
            Path sibling = p.resolveSibling("c.txt");
            assertEquals("/a/c.txt", sibling.toString());
        }
    }

    @Test
    void resolveSiblingOnRootReturnsOther() throws IOException {
        Path zip = createZip("test.zip", entry("x.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            Path root = fs.getPath("/");
            Path sibling = root.resolveSibling("c.txt");
            assertEquals("c.txt", sibling.toString());
        }
    }

    // -- Path traversal --

    @Test
    void pathTraversalEntryIsContained() throws IOException {
        Path zip = createZip("traversal.zip",
                entry("../../etc/passwd", "root:x:0:0"),
                entry("a/../b.txt", "b"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            // Paths with ".." segments are normalized before lookup, so entries
            // whose raw names contain traversal sequences are not reachable
            // via their literal path.
            assertThrows(NoSuchFileException.class,
                    () -> Files.readString(fs.getPath("/../../etc/passwd")));

            // "a/../b.txt" normalizes to "b.txt" which doesn't exist as an entry
            assertThrows(NoSuchFileException.class,
                    () -> Files.readString(fs.getPath("/a/../b.txt")));

            // normalize does not escape the archive
            Path normalized = fs.getPath("/../../etc/passwd").normalize();
            assertTrue(normalized.toString().startsWith("/"),
                    "Normalized path should stay within the archive: " + normalized);
        }
    }

    // -- Mixed compression methods --

    @Test
    void mixedStoredAndDeflated() throws IOException {
        Path zip = createZipMixedMethods("mixed.zip",
                storedEntry("stored.txt", "stored content"),
                deflatedEntry("deflated.txt", "deflated content"));

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("stored content", Files.readString(fs.getPath("/stored.txt")));
            assertEquals("deflated content", Files.readString(fs.getPath("/deflated.txt")));
        }
    }

    // -- Large number of entries --

    @Test
    void manyEntries() throws IOException {
        int count = 10_000;
        TestEntry[] entries = new TestEntry[count];
        for (int i = 0; i < count; i++) {
            entries[i] = entry("entry" + i + ".txt", "data" + i);
        }
        Path zip = createZip("many.zip", entries);

        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("data0", Files.readString(fs.getPath("/entry0.txt")));
            assertEquals("data9999", Files.readString(fs.getPath("/entry9999.txt")));
            assertFalse(Files.exists(fs.getPath("/entry10000.txt")));
            try (Stream<Path> stream = Files.list(fs.getPath("/"))) {
                assertEquals(count, stream.count());
            }
        }
    }

    // -- getPath with empty strings --

    @Test
    void getPathWithEmptyStrings() throws IOException {
        Path zip = createZip("test.zip", entry("a/b.txt", "x"));
        try (FileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertEquals("a/b.txt", fs.getPath("a", "", "b.txt").toString());
            assertEquals("a", fs.getPath("", "a").toString());
            assertEquals("", fs.getPath("").toString());
            assertEquals("", fs.getPath("", "").toString());
        }
    }

    // -- Direct API --

    @Test
    void entryExistsDirectApi() throws IOException {
        Path zip = createZip("test.zip",
                entry("a.txt", "alpha"),
                entry("dir/b.txt", "bravo"));

        try (ReadOnlyZipFileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertTrue(fs.entryExists("a.txt"));
            assertTrue(fs.entryExists("dir/b.txt"));
            assertTrue(fs.entryExists("dir"));
            assertTrue(fs.entryExists(""));
            assertFalse(fs.entryExists("missing.txt"));
            assertFalse(fs.entryExists("dir/missing.txt"));
        }
    }

    @Test
    void entryExistsWithTrailingSlash() throws IOException {
        Path zip = createZip("test.zip",
                entry("dir/file.txt", "content"));

        try (ReadOnlyZipFileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            assertTrue(fs.entryExists("dir"));
            assertTrue(fs.entryExists("dir/"));
            assertTrue(fs.entryExists(""));
            assertTrue(fs.entryExists("/"));
        }
    }

    // -- Helper methods --

    // -- Entry caching --

    @Test
    void cacheReturnsIdenticalContent() throws IOException {
        ReadOnlyZipFileSystem.CACHE_ENABLED = true;
        Path zip = createZip("cache.zip",
                entry("a.txt", "alpha"),
                entry("b.txt", "bravo"));

        try (ReadOnlyZipFileSystem fs = ReadOnlyZipFileSystem.open(zip)) {
            byte[] first = fs.readEntryData("a.txt");
            byte[] second = fs.readEntryData("a.txt");
            assertArrayEquals(first, second);

            byte[] b1 = fs.readEntryData("b.txt");
            assertFalse(Arrays.equals(first, b1));
        }
    }

    private Path createZip(String name, TestEntry... entries) throws IOException {
        return createZipWithMethod(name, ZipEntry.DEFLATED, entries);
    }

    private Path createZipWithMethod(String name, int method, TestEntry... entries)
            throws IOException {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (TestEntry te : entries) {
                ZipEntry ze = new ZipEntry(te.name);
                if (method == ZipEntry.STORED) {
                    ze.setMethod(ZipEntry.STORED);
                    ze.setSize(te.data.length);
                    ze.setCompressedSize(te.data.length);
                    java.util.zip.CRC32 crc = new java.util.zip.CRC32();
                    crc.update(te.data);
                    ze.setCrc(crc.getValue());
                }
                zos.putNextEntry(ze);
                zos.write(te.data);
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static TestEntry entry(String name, String content) {
        return new TestEntry(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static TestEntry entry(String name, byte[] data) {
        return new TestEntry(name, data);
    }

    private record TestEntry(String name, byte[] data) {
    }

    private record MethodEntry(String name, byte[] data, int method) {
    }

    private static MethodEntry storedEntry(String name, String content) {
        return new MethodEntry(name, content.getBytes(StandardCharsets.UTF_8), ZipEntry.STORED);
    }

    private static MethodEntry deflatedEntry(String name, String content) {
        return new MethodEntry(name, content.getBytes(StandardCharsets.UTF_8), ZipEntry.DEFLATED);
    }

    private Path createZipMixedMethods(String name, MethodEntry... entries) throws IOException {
        Path zip = tempDir.resolve(name);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (MethodEntry me : entries) {
                ZipEntry ze = new ZipEntry(me.name);
                ze.setMethod(me.method);
                if (me.method == ZipEntry.STORED) {
                    ze.setSize(me.data.length);
                    ze.setCompressedSize(me.data.length);
                    CRC32 crc = new CRC32();
                    crc.update(me.data);
                    ze.setCrc(crc.getValue());
                }
                zos.putNextEntry(ze);
                zos.write(me.data);
                zos.closeEntry();
            }
        }
        return zip;
    }

    private static final int CENTRAL_DIR_SIG = 0x02014b50;

    private static int findCentralDirectoryOffset(byte[] zip) {
        for (int i = zip.length - 22; i >= 0; i--) {
            if (readLeInt32(zip, i) == 0x06054b50) {
                return (int) readLeUint32(zip, i + 16);
            }
        }
        throw new IllegalStateException("EOCD not found");
    }

    private static void patchCentralDirectoryUncompressedSize(byte[] zip, long newSize) {
        int cdOff = findCentralDirectoryOffset(zip);
        if (readLeInt32(zip, cdOff) != CENTRAL_DIR_SIG) {
            throw new IllegalStateException("Not a central directory entry at offset " + cdOff);
        }
        writeLeUint32(zip, cdOff + 24, newSize);
    }

    private static void clearLocalHeaderDataDescriptorFlag(byte[] zip) {
        if (readLeInt32(zip, 0) != 0x04034b50) {
            throw new IllegalStateException("Not a local file header at offset 0");
        }
        // Clear bit 3 of the general-purpose flags at local header offset 6
        zip[6] = (byte) (zip[6] & ~0x08);
    }

    private static void patchLocalHeaderUncompressedSize(byte[] zip, long newSize) {
        if (readLeInt32(zip, 0) != 0x04034b50) {
            throw new IllegalStateException("Not a local file header at offset 0");
        }
        writeLeUint32(zip, 22, newSize);
    }

    private static long findCentralDirectoryCompressedSize(byte[] zip) {
        int cdOff = findCentralDirectoryOffset(zip);
        return readLeUint32(zip, cdOff + 20);
    }

    private static int readLeInt32(byte[] buf, int off) {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    private static long readLeUint32(byte[] buf, int off) {
        return readLeInt32(buf, off) & 0xFFFFFFFFL;
    }

    private static void writeLeUint32(byte[] buf, int off, long value) {
        buf[off] = (byte) (value & 0xFF);
        buf[off + 1] = (byte) ((value >> 8) & 0xFF);
        buf[off + 2] = (byte) ((value >> 16) & 0xFF);
        buf[off + 3] = (byte) ((value >> 24) & 0xFF);
    }

    /**
     * Builds a minimal ZIP64 archive with one STORED entry. The archive uses
     * ZIP64 sentinel values (0xFFFFFFFF) in the central directory and EOCD,
     * with actual values in the ZIP64 extra fields and ZIP64 EOCD record.
     */
    private static byte[] buildZip64Archive(String entryName, byte[] data) throws IOException {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        long crcValue = crc.getValue();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // -- Local file header --
        long localHeaderOffset = 0;
        // ZIP64 extra field: uncompressed size (8) + compressed size (8)
        byte[] localExtra = buildZip64ExtraField(data.length, data.length);

        ByteBuffer local = ByteBuffer.allocate(30 + nameBytes.length + localExtra.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50); // local file header signature
        local.putShort((short) 45); // version needed (4.5 for ZIP64)
        local.putShort((short) 0); // general purpose bit flag
        local.putShort((short) 0); // compression method: STORED
        local.putShort((short) 0); // last mod file time
        local.putShort((short) 0); // last mod file date
        local.putInt((int) crcValue); // crc-32
        local.putInt(0xFFFFFFFF); // compressed size (ZIP64 sentinel)
        local.putInt(0xFFFFFFFF); // uncompressed size (ZIP64 sentinel)
        local.putShort((short) nameBytes.length);
        local.putShort((short) localExtra.length);
        local.put(nameBytes);
        local.put(localExtra);
        out.write(local.array());
        out.write(data);

        // -- Central directory header --
        long cdOffset = out.size();
        // ZIP64 extra field: uncompressed size (8) + compressed size (8) + local header offset (8)
        byte[] cdExtra = buildZip64ExtraFieldWithOffset(data.length, data.length, localHeaderOffset);

        ByteBuffer cd = ByteBuffer.allocate(46 + nameBytes.length + cdExtra.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        cd.putInt(CENTRAL_DIR_SIG); // central directory signature
        cd.putShort((short) 45); // version made by
        cd.putShort((short) 45); // version needed
        cd.putShort((short) 0); // general purpose bit flag
        cd.putShort((short) 0); // compression method: STORED
        cd.putShort((short) 0); // last mod file time
        cd.putShort((short) 0); // last mod file date
        cd.putInt((int) crcValue); // crc-32
        cd.putInt(0xFFFFFFFF); // compressed size (ZIP64 sentinel)
        cd.putInt(0xFFFFFFFF); // uncompressed size (ZIP64 sentinel)
        cd.putShort((short) nameBytes.length);
        cd.putShort((short) cdExtra.length); // extra field length
        cd.putShort((short) 0); // file comment length
        cd.putShort((short) 0); // disk number start
        cd.putShort((short) 0); // internal file attributes
        cd.putInt(0); // external file attributes
        cd.putInt(0xFFFFFFFF); // local header offset (ZIP64 sentinel)
        cd.put(nameBytes);
        cd.put(cdExtra);
        out.write(cd.array());

        long cdSize = out.size() - cdOffset;

        // -- ZIP64 End of Central Directory record --
        long zip64EocdOffset = out.size();
        ByteBuffer zip64Eocd = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        zip64Eocd.putInt(0x06064b50); // ZIP64 EOCD signature
        zip64Eocd.putLong(44); // size of remaining record
        zip64Eocd.putShort((short) 45); // version made by
        zip64Eocd.putShort((short) 45); // version needed
        zip64Eocd.putInt(0); // this disk number
        zip64Eocd.putInt(0); // disk with CD start
        zip64Eocd.putLong(1); // total entries on this disk
        zip64Eocd.putLong(1); // total entries
        zip64Eocd.putLong(cdSize); // central directory size
        zip64Eocd.putLong(cdOffset); // central directory offset
        out.write(zip64Eocd.array());

        // -- ZIP64 End of Central Directory locator --
        ByteBuffer locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        locator.putInt(0x07064b50); // ZIP64 EOCD locator signature
        locator.putInt(0); // disk with ZIP64 EOCD
        locator.putLong(zip64EocdOffset);
        locator.putInt(1); // total number of disks
        out.write(locator.array());

        // -- End of Central Directory record --
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50); // EOCD signature
        eocd.putShort((short) 0); // this disk number
        eocd.putShort((short) 0); // disk with CD start
        eocd.putShort((short) 0xFFFF); // entries on this disk (ZIP64 sentinel)
        eocd.putShort((short) 0xFFFF); // total entries (ZIP64 sentinel)
        eocd.putInt(0xFFFFFFFF); // CD size (ZIP64 sentinel)
        eocd.putInt(0xFFFFFFFF); // CD offset (ZIP64 sentinel)
        eocd.putShort((short) 0); // comment length
        out.write(eocd.array());

        return out.toByteArray();
    }

    private static byte[] buildZip64ExtraField(long uncompressedSize, long compressedSize) {
        ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0001); // ZIP64 extra field tag
        buf.putShort((short) 16); // data size
        buf.putLong(uncompressedSize);
        buf.putLong(compressedSize);
        return buf.array();
    }

    private static byte[] buildZip64ExtraFieldWithOffset(long uncompressedSize, long compressedSize,
            long localHeaderOffset) {
        ByteBuffer buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0x0001); // ZIP64 extra field tag
        buf.putShort((short) 24); // data size
        buf.putLong(uncompressedSize);
        buf.putLong(compressedSize);
        buf.putLong(localHeaderOffset);
        return buf.array();
    }

    /**
     * Builds a minimal STORED ZIP archive with one entry that has an NTFS
     * extra field (tag 0x000a) containing the given Windows FILETIME mtime.
     */
    private static byte[] buildZipWithNtfsTimestamp(String entryName, byte[] data, long winTime)
            throws IOException {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        long crcValue = crc.getValue();

        // NTFS extra field: tag(2) + size(2) + reserved(4) + attrTag(2) + attrSize(2) + mtime(8) + atime(8) + ctime(8)
        ByteBuffer ntfsExtra = ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN);
        ntfsExtra.putShort((short) 0x000a); // NTFS tag
        ntfsExtra.putShort((short) 32); // data size
        ntfsExtra.putInt(0); // reserved
        ntfsExtra.putShort((short) 0x0001); // attribute tag
        ntfsExtra.putShort((short) 24); // attribute size
        ntfsExtra.putLong(winTime); // mtime
        ntfsExtra.putLong(winTime); // atime
        ntfsExtra.putLong(winTime); // ctime
        byte[] extra = ntfsExtra.array();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // Local file header
        ByteBuffer local = ByteBuffer.allocate(30 + nameBytes.length + extra.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50);
        local.putShort((short) 20); // version needed
        local.putShort((short) 0); // flags
        local.putShort((short) 0); // method: STORED
        local.putShort((short) 0); // DOS time
        local.putShort((short) 0); // DOS date
        local.putInt((int) crcValue);
        local.putInt(data.length); // compressed size
        local.putInt(data.length); // uncompressed size
        local.putShort((short) nameBytes.length);
        local.putShort((short) extra.length);
        local.put(nameBytes);
        local.put(extra);
        out.write(local.array());
        out.write(data);

        // Central directory
        long cdOffset = out.size();
        ByteBuffer cd = ByteBuffer.allocate(46 + nameBytes.length + extra.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        cd.putInt(CENTRAL_DIR_SIG);
        cd.putShort((short) 20); // version made by
        cd.putShort((short) 20); // version needed
        cd.putShort((short) 0); // flags
        cd.putShort((short) 0); // method: STORED
        cd.putShort((short) 0); // DOS time
        cd.putShort((short) 0); // DOS date
        cd.putInt((int) crcValue);
        cd.putInt(data.length); // compressed size
        cd.putInt(data.length); // uncompressed size
        cd.putShort((short) nameBytes.length);
        cd.putShort((short) extra.length); // extra field length
        cd.putShort((short) 0); // comment length
        cd.putShort((short) 0); // disk number start
        cd.putShort((short) 0); // internal attrs
        cd.putInt(0); // external attrs
        cd.putInt(0); // local header offset
        cd.put(nameBytes);
        cd.put(extra);
        out.write(cd.array());

        long cdSize = out.size() - cdOffset;

        // EOCD
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0);
        eocd.putShort((short) 1); // entries on this disk
        eocd.putShort((short) 1); // total entries
        eocd.putInt((int) cdSize);
        eocd.putInt((int) cdOffset);
        eocd.putShort((short) 0);
        out.write(eocd.array());

        return out.toByteArray();
    }

    /**
     * Builds a minimal DEFLATED ZIP archive with one entry that uses a data
     * descriptor (general-purpose bit 3 set). Sizes and CRC are zeroed in both
     * the local and central directory headers; the actual values appear only in
     * the data descriptor following the compressed data.
     */
    private static byte[] buildZipWithDataDescriptor(String entryName, byte[] data) throws IOException {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);

        CRC32 crc = new CRC32();
        crc.update(data);
        long crcValue = crc.getValue();

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] compBuf = new byte[data.length + 256];
        int compLen = deflater.deflate(compBuf);
        deflater.end();
        byte[] compressed = new byte[compLen];
        System.arraycopy(compBuf, 0, compressed, 0, compLen);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // -- Local file header (sizes and CRC zeroed, bit 3 set) --
        ByteBuffer local = ByteBuffer.allocate(30 + nameBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50);
        local.putShort((short) 20); // version needed
        local.putShort((short) 0x08); // flags: bit 3 = data descriptor
        local.putShort((short) 8); // method: DEFLATED
        local.putShort((short) 0); // DOS time
        local.putShort((short) 0); // DOS date
        local.putInt(0); // CRC-32 (deferred to data descriptor)
        local.putInt(0); // compressed size (deferred)
        local.putInt(0); // uncompressed size (deferred)
        local.putShort((short) nameBytes.length);
        local.putShort((short) 0); // extra field length
        local.put(nameBytes);
        out.write(local.array());
        out.write(compressed);

        // -- Data descriptor (with signature) --
        ByteBuffer dd = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        dd.putInt(0x08074b50); // data descriptor signature
        dd.putInt((int) crcValue);
        dd.putInt(compLen); // compressed size
        dd.putInt(data.length); // uncompressed size
        out.write(dd.array());

        // -- Central directory (sizes zeroed, bit 3 set) --
        long cdOffset = out.size();
        ByteBuffer cd = ByteBuffer.allocate(46 + nameBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        cd.putInt(CENTRAL_DIR_SIG);
        cd.putShort((short) 20); // version made by
        cd.putShort((short) 20); // version needed
        cd.putShort((short) 0x08); // flags: bit 3
        cd.putShort((short) 8); // method: DEFLATED
        cd.putShort((short) 0); // DOS time
        cd.putShort((short) 0); // DOS date
        cd.putInt((int) crcValue); // CRC-32 (required for verification)
        cd.putInt(compLen); // compressed size (required to read data)
        cd.putInt(0); // uncompressed size (zeroed)
        cd.putShort((short) nameBytes.length);
        cd.putShort((short) 0); // extra field length
        cd.putShort((short) 0); // comment length
        cd.putShort((short) 0); // disk number start
        cd.putShort((short) 0); // internal attrs
        cd.putInt(0); // external attrs
        cd.putInt(0); // local header offset
        cd.put(nameBytes);
        out.write(cd.array());

        long cdSize = out.size() - cdOffset;

        // -- EOCD --
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0);
        eocd.putShort((short) 1);
        eocd.putShort((short) 1);
        eocd.putInt((int) cdSize);
        eocd.putInt((int) cdOffset);
        eocd.putShort((short) 0);
        out.write(eocd.array());

        return out.toByteArray();
    }

    /**
     * Builds a minimal ZIP64 DEFLATED archive with one entry that uses a
     * data descriptor with 64-bit fields. Version needed is 45 (ZIP64),
     * sizes are zeroed in both headers, and the actual values appear only
     * in the ZIP64 data descriptor.
     */
    private static byte[] buildZip64WithDataDescriptor(String entryName, byte[] data) throws IOException {
        byte[] nameBytes = entryName.getBytes(StandardCharsets.UTF_8);

        CRC32 crc = new CRC32();
        crc.update(data);
        long crcValue = crc.getValue();

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(data);
        deflater.finish();
        byte[] compBuf = new byte[data.length + 256];
        int compLen = deflater.deflate(compBuf);
        deflater.end();
        byte[] compressed = new byte[compLen];
        System.arraycopy(compBuf, 0, compressed, 0, compLen);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // -- Local file header (version 45, bit 3 set, sizes zeroed) --
        ByteBuffer local = ByteBuffer.allocate(30 + nameBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        local.putInt(0x04034b50);
        local.putShort((short) 45); // version needed (ZIP64)
        local.putShort((short) 0x08); // flags: bit 3 = data descriptor
        local.putShort((short) 8); // method: DEFLATED
        local.putShort((short) 0); // DOS time
        local.putShort((short) 0); // DOS date
        local.putInt(0); // CRC-32 (deferred)
        local.putInt(0); // compressed size (deferred)
        local.putInt(0); // uncompressed size (deferred)
        local.putShort((short) nameBytes.length);
        local.putShort((short) 0); // extra field length
        local.put(nameBytes);
        out.write(local.array());
        out.write(compressed);

        // -- ZIP64 data descriptor (with signature) --
        // signature(4) + crc32(4) + compressedSize(8) + uncompressedSize(8)
        ByteBuffer dd = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        dd.putInt(0x08074b50); // data descriptor signature
        dd.putInt((int) crcValue);
        dd.putLong(compLen); // compressed size (64-bit)
        dd.putLong(data.length); // uncompressed size (64-bit)
        out.write(dd.array());

        // -- Central directory (ZIP64 sentinels + extra field, bit 3 set) --
        long cdOffset = out.size();
        byte[] cdExtra = buildZip64ExtraFieldWithOffset(0, 0, 0);
        ByteBuffer cd = ByteBuffer.allocate(46 + nameBytes.length + cdExtra.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        cd.putInt(CENTRAL_DIR_SIG);
        cd.putShort((short) 45); // version made by
        cd.putShort((short) 45); // version needed
        cd.putShort((short) 0x08); // flags: bit 3
        cd.putShort((short) 8); // method: DEFLATED
        cd.putShort((short) 0); // DOS time
        cd.putShort((short) 0); // DOS date
        cd.putInt((int) crcValue);
        cd.putInt(compLen); // compressed size (needed to read data)
        cd.putInt(0); // uncompressed size (zeroed — resolved from data descriptor)
        cd.putShort((short) nameBytes.length);
        cd.putShort((short) cdExtra.length);
        cd.putShort((short) 0); // comment length
        cd.putShort((short) 0); // disk number start
        cd.putShort((short) 0); // internal attrs
        cd.putInt(0); // external attrs
        cd.putInt(0xFFFFFFFF); // local header offset (ZIP64 sentinel)
        cd.put(nameBytes);
        cd.put(cdExtra);
        out.write(cd.array());

        long cdSize = out.size() - cdOffset;

        // -- ZIP64 EOCD --
        long zip64EocdOffset = out.size();
        ByteBuffer zip64Eocd = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN);
        zip64Eocd.putInt(0x06064b50);
        zip64Eocd.putLong(44);
        zip64Eocd.putShort((short) 45);
        zip64Eocd.putShort((short) 45);
        zip64Eocd.putInt(0);
        zip64Eocd.putInt(0);
        zip64Eocd.putLong(1);
        zip64Eocd.putLong(1);
        zip64Eocd.putLong(cdSize);
        zip64Eocd.putLong(cdOffset);
        out.write(zip64Eocd.array());

        // -- ZIP64 EOCD locator --
        ByteBuffer locator = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        locator.putInt(0x07064b50);
        locator.putInt(0);
        locator.putLong(zip64EocdOffset);
        locator.putInt(1);
        out.write(locator.array());

        // -- EOCD --
        ByteBuffer eocd = ByteBuffer.allocate(22).order(ByteOrder.LITTLE_ENDIAN);
        eocd.putInt(0x06054b50);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0);
        eocd.putShort((short) 0xFFFF);
        eocd.putShort((short) 0xFFFF);
        eocd.putInt(0xFFFFFFFF);
        eocd.putInt(0xFFFFFFFF);
        eocd.putShort((short) 0);
        out.write(eocd.array());

        return out.toByteArray();
    }
}
