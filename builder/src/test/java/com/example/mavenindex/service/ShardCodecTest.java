package com.example.mavenindex.service;

import com.example.mavenindex.model.RawArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ShardCodecTest {
    @TempDir
    Path tempDir;

    @Test
    void roundTripsRawArtifactRecords() throws Exception {
        Path shard = tempDir.resolve("000.bin");
        try (ShardCodec.ShardWriter writer = ShardCodec.openWriter(shard)) {
            writer.write(new RawArtifact("org.example", "demo", "1.0.0", "jar", 1000L));
            writer.write(new RawArtifact("org.example", "demo", "1.0.1", null, null));
        }

        try (ShardCodec.ShardReader reader = ShardCodec.openReader(shard)) {
            RawArtifact first = reader.next();
            assertEquals("org.example", first.groupId());
            assertEquals("demo", first.artifactId());
            assertEquals("1.0.0", first.version());
            assertEquals("jar", first.packaging());
            assertEquals(1000L, first.timestamp());

            RawArtifact second = reader.next();
            assertEquals("1.0.1", second.version());
            assertNull(second.packaging());
            assertNull(second.timestamp());
            assertNull(reader.next());
        }
    }
}
