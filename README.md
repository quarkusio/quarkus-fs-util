# quarkus-fs-util

Utilities for low-level filesystem operations.

## Read-Only ZIP FileSystem

The `io.quarkus.fs.util.rozip` package contains a read-only `java.nio.file.FileSystem` implementation for ZIP/JAR archives. It was built to replace the JDK's `ZipFileSystem` in Quarkus, where thread interrupts during augmentation could close shared file channels and corrupt the build.

### Why it exists

The JDK's `ZipFileSystem` reads through a `FileChannel`, which is an `InterruptibleChannel`. If any thread that shares the channel is interrupted, the channel is closed for all threads ([JDK-8316882](https://bugs.openjdk.org/browse/JDK-8316882)). This implementation uses `RandomAccessFile` instead, whose I/O operations are not affected by thread interrupts.

### Usage

```java
// Via the public API (returns FileSystem)
FileSystem fs = ZipUtils.openReadOnly(path);

// Or directly (returns the concrete type for direct API access)
ReadOnlyZipFileSystem fs = ReadOnlyZipFileSystem.open(path);
```

Each call opens a new, independent file handle. No caching or deduplication is performed — callers that open the same archive multiple times should cache the returned instance themselves.

### How it works

Entry data is read on demand from the underlying `RandomAccessFile`. All reads are synchronized on the file handle for thread safety. Compressed data is decompressed into a `byte[]` using a pooled `Inflater` (up to 8 instances per filesystem). The full decompressed content is returned to the caller — there is no streaming API.

### Comparison with JDK ZipFileSystem

| | Rozip | JDK ZipFileSystem |
|---|---|---|
| **Thread interrupt safety** | Immune — uses `RandomAccessFile` | Vulnerable — `FileChannel` is an `InterruptibleChannel` ([JDK-8316882](https://bugs.openjdk.org/browse/JDK-8316882)) |
| **Read/write** | Read-only | Read-write |
| **NIO compatibility** | Read-only `java.nio.file` API | Full |
| **Direct API** | `entryExists(String)` bypasses NIO dispatch | N/A |
| **ServiceLoader discovery** | No — access via `ZipUtils.openReadOnly()` or `ReadOnlyZipFileSystem.open()` | Yes |
| **Central directory memory** | Compact sorted arrays (~60% less) | `HashMap`-based |
| **Central directory size limit** | 256 MB | No limit |
| **Entry data reads** | Fully materialized `byte[]` per read | Memory-mapped or channel-based streaming |
| **Entry caching** | Opt-in `SoftReference` cache (`-Drozip.cache=true`) | Internal caching |
| **Inflater pooling** | Per-filesystem pool (up to 8) | Per-filesystem pool |
| **Compression methods** | STORED, DEFLATED | STORED, DEFLATED |
| **ZIP64** | Yes (entries capped at 256 MB) | Yes (no entry size cap) |
| **Data descriptors** | Yes (32-bit and ZIP64) | Yes |
| **Extended timestamps** | Unix (0x5455) and NTFS (0x000a) | Unix (0x5455) and NTFS (0x000a) |
| **Entry name encoding** | UTF-8 only | Configurable (default UTF-8, respects EFS flag) |
| **CRC-32 verification** | Yes | Yes |
| **Zip bomb protection** | 256 MB entry cap, 1000:1 ratio limit | No |
| **Encryption** | No | No |
| **Multi-disk archives** | No | No |

### Limitations and Quarkus impact

- **Read-only.** Write operations (`Files.copy()`, `Files.move()`, `Files.delete()`, `Files.newOutputStream()`, `Files.createDirectory()`) throw `ReadOnlyFileSystemException`. Quarkus only reads JARs during augmentation and at runtime, so this is not a limitation in practice.

- **UTF-8 entry names only.** The Language Encoding Flag (general-purpose bit 11) is ignored; all names are decoded as UTF-8. Archives created by older tools with CP437-encoded non-ASCII filenames will produce incorrect entry names. Modern JARs universally use UTF-8, so this does not affect Quarkus dependencies.

- **256 MB entry and central directory cap.** Individual entries and the central directory are each limited to 256 MB. This is a zip-bomb defense. Quarkus JARs are well below this threshold; an uber-JAR would need hundreds of thousands of entries to approach the central directory limit.

- **No encryption support.** Encrypted ZIP entries cannot be read. Quarkus itself does not use encrypted JARs, but if a user dependency ships as an encrypted JAR, it will fail to open at build time. Note that the JDK `ZipFileSystem` also does not support encryption, so this is not a regression.

- **No multi-disk archive support.** Split/spanned archives are not supported. Quarkus dependencies are always single-file JARs.

- **No nested archive support.** Reading a ZIP-in-ZIP (e.g. a JAR inside an uber-JAR) requires extracting the inner bytes with `Files.readAllBytes()` and opening separately. This matches the JDK behavior. Quarkus handles nested JARs at a higher level and does not rely on filesystem-level nesting.

- **65535-entry edge case.** A non-ZIP64 archive with exactly 65535 entries is misidentified as ZIP64, causing an `IOException` if no ZIP64 end-of-central-directory record is present. In practice, most tools emit ZIP64 structures at that entry count, and Quarkus JARs are well below this threshold.

- **No ServiceLoader discovery.** The filesystem provider is not registered via `META-INF/services` and cannot be used with `FileSystems.newFileSystem(URI, ...)`. Access is through `ZipUtils.openReadOnly()` or `ReadOnlyZipFileSystem.open()`. This is intentional — Quarkus controls all call sites and does not need URI-based discovery.

## Release

To release a new version, follow these steps:

https://github.com/smallrye/smallrye/wiki/Release-Process#releasing

The staging repository is automatically closed. The sync with Maven Central should take ~30 minutes.
