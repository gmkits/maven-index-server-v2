package com.example.mavenindex.service;

import com.example.mavenindex.model.RawArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceIndexReaderTest {
    @TempDir
    Path tempDir;

    @Test
    void readsPackedRecordsAndSkipsUnusedFields() throws Exception {
        Path gz = tempDir.resolve("index.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(gz)))) {
            out.writeByte(1);
            out.writeLong(123456789L);
            writeRecord(out,
                    field('u', "org.example|demo-lib|1.0.0|NA"),
                    field('m', "1000"),
                    field('i', "jar|extra"),
                    field('d', "description should be skipped"));
        }

        try (SourceIndexReader reader = new SourceIndexReader(gz)) {
            assertEquals(1, reader.formatVersion());
            assertEquals(123456789L, reader.sourceTimestamp());
            RawArtifact artifact = reader.next();
            assertEquals("org.example", artifact.groupId());
            assertEquals("demo-lib", artifact.artifactId());
            assertEquals("1.0.0", artifact.version());
            assertEquals("jar", artifact.packaging());
            assertEquals(1000L, artifact.timestamp());
            assertNull(reader.next());
        }
    }

    static Field field(char name, String value) {
        return new Field(name, value.getBytes(StandardCharsets.UTF_8));
    }

    static void writeRecord(DataOutputStream out, Field... fields) throws Exception {
        out.writeInt(fields.length);
        for (Field field : fields) {
            out.writeByte(5);
            out.writeShort(1);
            out.writeByte((byte) field.name());
            out.writeInt(field.value().length);
            out.write(field.value());
        }
    }

    record Field(char name, byte[] value) {
    }
}
