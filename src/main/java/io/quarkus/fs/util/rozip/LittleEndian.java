package io.quarkus.fs.util.rozip;

/**
 * Little-endian byte-reading utilities for ZIP binary parsing.
 */
final class LittleEndian {

    private LittleEndian() {
    }

    /**
     * Reads an unsigned 16-bit integer from two bytes in little-endian order.
     *
     * @param buf byte array to read from
     * @param off offset of the first byte
     * @return the unsigned 16-bit value as an {@code int} in the range 0..65535
     */
    static int readUint16(byte[] buf, int off) {
        return (buf[off] & 0xFF) | ((buf[off + 1] & 0xFF) << 8);
    }

    /**
     * Reads an unsigned 32-bit integer from four bytes in little-endian order.
     *
     * @param buf byte array to read from
     * @param off offset of the first byte
     * @return the unsigned 32-bit value as a {@code long} in the range 0..4294967295
     */
    static long readUint32(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24);
    }

    /**
     * Reads a signed 32-bit integer from four bytes in little-endian order.
     *
     * @param buf byte array to read from
     * @param off offset of the first byte
     * @return the signed 32-bit value as an {@code int}
     */
    static int readInt32(byte[] buf, int off) {
        return (buf[off] & 0xFF)
                | ((buf[off + 1] & 0xFF) << 8)
                | ((buf[off + 2] & 0xFF) << 16)
                | ((buf[off + 3] & 0xFF) << 24);
    }

    /**
     * Reads a 64-bit integer from eight bytes in little-endian order.
     * <p>
     * The result is returned as a signed {@code long}. Values with bit 63 set will appear negative.
     * In practice, ZIP64 fields never exceed approximately 2<sup>63</sup>, which is the same
     * limitation imposed by the JDK's {@code ZipUtils}.
     *
     * @param buf byte array to read from
     * @param off offset of the first byte
     * @return the 64-bit value as a signed {@code long}
     */
    static long readUint64(byte[] buf, int off) {
        return (buf[off] & 0xFFL)
                | ((buf[off + 1] & 0xFFL) << 8)
                | ((buf[off + 2] & 0xFFL) << 16)
                | ((buf[off + 3] & 0xFFL) << 24)
                | ((buf[off + 4] & 0xFFL) << 32)
                | ((buf[off + 5] & 0xFFL) << 40)
                | ((buf[off + 6] & 0xFFL) << 48)
                | ((buf[off + 7] & 0xFFL) << 56);
    }
}
