package io.quarkus.fs.util.rozip;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Little-endian byte-reading utilities for ZIP binary parsing.
 * <p>
 * Uses {@link VarHandle} byte-array views to read multi-byte primitives
 * in a single operation rather than manual byte shifting.
 */
final class LittleEndian {

    private static final VarHandle SHORT_LE = MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_LE = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle LONG_LE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

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
        return (short) SHORT_LE.get(buf, off) & 0xFFFF;
    }

    /**
     * Reads an unsigned 32-bit integer from four bytes in little-endian order.
     *
     * @param buf byte array to read from
     * @param off offset of the first byte
     * @return the unsigned 32-bit value as a {@code long} in the range 0..4294967295
     */
    static long readUint32(byte[] buf, int off) {
        return (int) INT_LE.get(buf, off) & 0xFFFFFFFFL;
    }

    /**
     * Reads a signed 32-bit integer from four bytes in little-endian order.
     *
     * @param buf byte array to read from
     * @param off offset of the first byte
     * @return the signed 32-bit value as an {@code int}
     */
    static int readInt32(byte[] buf, int off) {
        return (int) INT_LE.get(buf, off);
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
        return (long) LONG_LE.get(buf, off);
    }
}
