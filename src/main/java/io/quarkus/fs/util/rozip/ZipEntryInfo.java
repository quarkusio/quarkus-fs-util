package io.quarkus.fs.util.rozip;

/**
 * Immutable metadata for a single ZIP entry, parsed from the central directory.
 * <p>
 * Instances are created during {@link ZipCentralDirectory} parsing and remain
 * constant for the lifetime of the {@link ReadOnlyZipFileSystem}.
 *
 * @param name the entry path within the archive (e.g. {@code "com/example/Foo.class"})
 * @param compressedSize compressed size in bytes
 * @param uncompressedSize uncompressed size in bytes
 * @param compressionMethod compression method ({@code 0} = STORED, {@code 8} = DEFLATED)
 * @param crc32 CRC-32 checksum of the uncompressed data
 * @param localHeaderOffset byte offset of this entry's local file header in the ZIP file
 * @param directory whether this entry represents a directory
 * @param lastModifiedTime last-modified timestamp in epoch milliseconds
 */
record ZipEntryInfo(
        String name,
        long compressedSize,
        long uncompressedSize,
        int compressionMethod,
        long crc32,
        long localHeaderOffset,
        boolean directory,
        long lastModifiedTime) {

    static final String ROOT_ENTRY_NAME = "";
}
