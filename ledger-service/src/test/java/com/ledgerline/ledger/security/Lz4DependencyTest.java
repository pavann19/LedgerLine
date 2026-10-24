package com.ledgerline.ledger.security;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class Lz4DependencyTest {
    @Test void patchedKafkaCompressionDependencyLoadsAndRoundTrips() throws Exception {
        Class<?> factoryType = Class.forName("net.jpountz.lz4.LZ4Factory");
        assertTrue(factoryType.getProtectionDomain().getCodeSource().getLocation().toString().contains("1.11.4"),
            "CVE-2026-106451 requires the reviewed patched LZ4 dependency");
        Object factory = factoryType.getMethod("fastestJavaInstance").invoke(null);
        Object compressor = factoryType.getMethod("fastCompressor").invoke(factory);
        Object decompressor = factoryType.getMethod("safeDecompressor").invoke(factory);
        byte[] input = "ledgerline-compression-regression".repeat(100).getBytes(StandardCharsets.UTF_8);
        byte[] compressed = (byte[]) Class.forName("net.jpountz.lz4.LZ4Compressor")
            .getMethod("compress", byte[].class).invoke(compressor, (Object) input);
        byte[] restored = (byte[]) Class.forName("net.jpountz.lz4.LZ4SafeDecompressor")
            .getMethod("decompress", byte[].class, int.class).invoke(decompressor, compressed, input.length);
        assertArrayEquals(input, restored);
    }
}
