package com.example.mavenindex.service;

import com.example.mavenindex.model.VersionEntry;
import com.github.luben.zstd.ZstdInputStream;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionBlobCodecTest {
    @Test
    void encodesCompactZstdBinaryBlob() throws Exception {
        byte[] blob = VersionBlobCodec.encode(List.of(
                new VersionEntry("1.0.1", "jar", 3000L, true),
                new VersionEntry("1.1.0-beta", null, null, false)
        ));

        byte[] raw;
        try (ZstdInputStream in = new ZstdInputStream(new ByteArrayInputStream(blob))) {
            raw = in.readAllBytes();
        }

        List<VersionEntry> decoded = VersionBlobCodec.decodeUncompressed(raw);
        assertEquals(2, decoded.size());
        assertEquals("1.0.1", decoded.get(0).version());
        assertEquals("jar", decoded.get(0).packaging());
        assertEquals(3000L, decoded.get(0).timestamp());
        assertTrue(decoded.get(0).stable());
        assertNull(decoded.get(1).packaging());
        assertNull(decoded.get(1).timestamp());
    }
}
