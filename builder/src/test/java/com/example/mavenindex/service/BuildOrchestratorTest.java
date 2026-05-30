package com.example.mavenindex.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.zip.GZIPOutputStream;

import static com.example.mavenindex.service.SourceIndexReaderTest.field;
import static com.example.mavenindex.service.SourceIndexReaderTest.writeRecord;
import static org.junit.jupiter.api.Assertions.assertEquals;

class BuildOrchestratorTest {
    @TempDir
    Path tempDir;

    @Test
    void buildsV2SqliteFromPackedFixture() throws Exception {
        Path gz = tempDir.resolve("index.gz");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(gz)))) {
            out.writeByte(1);
            out.writeLong(123456789L);
            writeRecord(out, field('u', "org.example|demo-lib|1.0.0|NA"), field('m', "1000"), field('i', "jar"));
            writeRecord(out, field('u', "org.example|demo-lib|1.1.0-beta|NA"), field('m', "2000"), field('i', "jar"));
            writeRecord(out, field('u', "org.example|demo-lib|1.0.1|NA"), field('m', "3000"), field('i', "jar"));
            writeRecord(out, field('u', "org.example|other|2.0.0|NA"), field('m', "4000"), field('i', "pom"));
        }

        Path output = tempDir.resolve("release");
        new BuildOrchestrator().build(new BuildOptions(gz, output, false, 0, 4, 2));
        String metadata = Files.readString(output.resolve("metadata.json"));
        org.junit.jupiter.api.Assertions.assertTrue(metadata.contains("\"sourceSha256\""));
        org.junit.jupiter.api.Assertions.assertTrue(metadata.contains("\"sourceSizeBytes\""));

        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + output.resolve("maven-index.db"))) {
            try (var rs = conn.createStatement().executeQuery(
                    "SELECT value FROM meta WHERE key='schema_version'")) {
                rs.next();
                assertEquals("2", rs.getString(1));
            }
            try (var rs = conn.createStatement().executeQuery("SELECT count(*) FROM artifacts")) {
                rs.next();
                assertEquals(2, rs.getInt(1));
            }

            try (var ps = conn.prepareStatement("""
                    SELECT latest_version, latest_stable_version, version_count
                    FROM artifacts WHERE ga = ?
                    """)) {
                ps.setString(1, "org.example:demo-lib");
                try (var rs = ps.executeQuery()) {
                    rs.next();
                    assertEquals("1.1.0-beta", rs.getString("latest_version"));
                    assertEquals("1.0.1", rs.getString("latest_stable_version"));
                    assertEquals(3, rs.getInt("version_count"));
                }
            }
        }
    }
}
