package com.ledgerline.projection.security;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SnappyDependencyTest {
    private Class<?> codec() throws ClassNotFoundException {
        Class<?> type = Class.forName("org.xerial.snappy.Snappy");
        assertTrue(type.getProtectionDomain().getCodeSource().getLocation().toString()
            .contains("snappy-java-1.1.10.9"), "Load the reviewed Kafka compression patch");
        return type;
    }

    @Test void patchedCodecPreservesByteAndTypedArrayRoundTrips() throws Exception {
        Class<?> type = codec();
        byte[] input = "ledgerline-kafka-codec".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = (byte[]) type.getMethod("compress", byte[].class).invoke(null, (Object) input);
        assertArrayEquals(input, (byte[]) type.getMethod("uncompress", byte[].class).invoke(null, (Object) compressed));
        int[] values = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE};
        byte[] encoded = (byte[]) type.getMethod("compress", int[].class).invoke(null, (Object) values);
        assertArrayEquals(values, (int[]) type.getMethod("uncompressIntArray", byte[].class).invoke(null, (Object) encoded));
    }

    @Test void malformedLengthsAreRejectedBeforeUnsafeAllocationOrNativeWrite() throws Exception {
        Class<?> type = codec();
        byte[] impossibleLength = {(byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, 0x07};
        var lengthFailure = assertThrows(InvocationTargetException.class,
            () -> type.getMethod("uncompressedLength", byte[].class).invoke(null, (Object) impossibleLength));
        assertInstanceOf(IOException.class, lengthFailure.getCause());
        byte[] misaligned = (byte[]) type.getMethod("compress", byte[].class)
            .invoke(null, (Object) new byte[] {1, 2, 3});
        var arrayFailure = assertThrows(InvocationTargetException.class,
            () -> type.getMethod("uncompressIntArray", byte[].class).invoke(null, (Object) misaligned));
        assertInstanceOf(IOException.class, arrayFailure.getCause());
    }
}
