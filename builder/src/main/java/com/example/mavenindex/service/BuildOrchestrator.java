package com.example.mavenindex.service;

import com.example.mavenindex.model.AggregatedArtifact;
import com.example.mavenindex.model.RawArtifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class BuildOrchestrator {
    private static final Logger LOG = LoggerFactory.getLogger(BuildOrchestrator.class);
    private static final String SCHEMA_VERSION = "2";
    private static final int AGGREGATE_BATCH_SIZE = 512;
    private static final AggregatedBatch POISON = new AggregatedBatch(List.of());

    public void build(BuildOptions options) throws Exception {
        long startTime = System.currentTimeMillis();
        Path outputDir = options.outputDir();
        Path workDir = outputDir.resolveSibling(outputDir.getFileName() + ".work");
        Path rawShardsDir = workDir.resolve("raw-shards");
        Path dbPath = workDir.resolve("maven-index.db");
        String sourceSha256 = sha256Hex(options.sourceGzPath());
        long sourceSizeBytes = Files.size(options.sourceGzPath());

        deleteRecursively(workDir);
        Files.createDirectories(rawShardsDir);

        long sourceRecords;
        long validRecords;
        try {
            ShardStats shardStats = shardSource(options, rawShardsDir);
            sourceRecords = shardStats.sourceRecords();
            validRecords = shardStats.validRecords();

            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                configureBuildConnection(conn);
                initBaseSchema(conn);
                BuildStats buildStats = aggregateAndWrite(options, rawShardsDir, conn);
                createSearchStructures(conn, options.enableTrigram());
                finishDatabase(conn);

                Files.deleteIfExists(dbPath.resolveSibling("maven-index.db-shm"));
                Files.deleteIfExists(dbPath.resolveSibling("maven-index.db-wal"));

                deleteRecursively(rawShardsDir);
                long elapsed = System.currentTimeMillis() - startTime;
                long dbSize = Files.size(dbPath);
                writeMetadata(workDir, options, sourceSha256, sourceSizeBytes, sourceRecords, validRecords, buildStats, elapsed, dbSize);
            }

            atomicSwitch(workDir, outputDir);
        } catch (Exception e) {
            deleteRecursively(workDir);
            throw e;
        }
    }

    private ShardStats shardSource(BuildOptions options, Path rawShardsDir) throws IOException {
        LOG.info("Phase 1: Sharding source index to binary shards...");
        ShardCodec.ShardWriter[] writers = new ShardCodec.ShardWriter[options.shardCount()];
        for (int i = 0; i < writers.length; i++) {
            writers[i] = ShardCodec.openWriter(rawShardsDir.resolve("%03x.bin".formatted(i)));
        }

        long sourceRecords = 0;
        long validRecords = 0;
        try (SourceIndexReader reader = new SourceIndexReader(options.sourceGzPath())) {
            RawArtifact raw;
            while ((raw = reader.next()) != null) {
                if (options.maxRecords() > 0 && sourceRecords >= options.maxRecords()) {
                    break;
                }
                sourceRecords++;
                if (raw.version().isBlank()) {
                    continue;
                }
                int shardIdx = shardIndex(raw.groupId(), raw.artifactId(), options.shardCount());
                writers[shardIdx].write(raw);
                validRecords++;
                if (sourceRecords % 5_000_000 == 0) {
                    LOG.info("Read {} source records, wrote {} valid records", sourceRecords, validRecords);
                }
            }
        } finally {
            for (ShardCodec.ShardWriter writer : writers) {
                if (writer != null) {
                    writer.close();
                }
            }
        }

        LOG.info("Phase 1 complete: {} source records, {} valid records, {} shards",
                sourceRecords, validRecords, options.shardCount());
        return new ShardStats(sourceRecords, validRecords);
    }

    private BuildStats aggregateAndWrite(BuildOptions options, Path rawShardsDir, Connection conn) throws Exception {
        LOG.info("Phase 2: Aggregating shards with {} workers and writing SQLite...", options.workers());
        BlockingQueue<AggregatedBatch> queue = new ArrayBlockingQueue<>(Math.max(8, options.workers() * 4));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong processedShards = new AtomicLong();
        CountDownLatch done = new CountDownLatch(options.shardCount());
        BuildStats stats = new BuildStats();

        Thread writerThread = new Thread(() -> {
            try {
                writeArtifacts(conn, queue, stats);
            } catch (Throwable t) {
                failure.compareAndSet(null, t);
            }
        }, "sqlite-writer");
        writerThread.start();

        ExecutorService pool = Executors.newFixedThreadPool(options.workers());
        for (int shardIdx = 0; shardIdx < options.shardCount(); shardIdx++) {
            final int currentShard = shardIdx;
            pool.submit(() -> {
                try {
                    processShard(rawShardsDir, currentShard, queue, failure);
                    long doneCount = processedShards.incrementAndGet();
                    if (doneCount % 32 == 0 || doneCount == options.shardCount()) {
                        LOG.info("Shards processed: {}/{}", doneCount, options.shardCount());
                    }
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                } finally {
                    done.countDown();
                }
            });
        }

        pool.shutdown();
        while (!done.await(1, TimeUnit.SECONDS)) {
            Throwable t = failure.get();
            if (t != null) {
                pool.shutdownNow();
                break;
            }
        }

        if (writerThread.isAlive()) {
            while (!queue.offer(POISON, 1, TimeUnit.SECONDS)) {
                if (!writerThread.isAlive()) {
                    break;
                }
            }
        }
        writerThread.join();

        Throwable t = failure.get();
        if (t != null) {
            if (t instanceof Exception exception) {
                throw exception;
            }
            throw new RuntimeException(t);
        }

        LOG.info("Phase 2 complete: {} artifacts, {} distinct versions", stats.artifactCount.get(), stats.versionCount.get());
        return stats;
    }

    private void processShard(
            Path rawShardsDir,
            int shardIdx,
            BlockingQueue<AggregatedBatch> queue,
            AtomicReference<Throwable> failure
    ) throws IOException, InterruptedException {
        Path shardFile = rawShardsDir.resolve("%03x.bin".formatted(shardIdx));
        if (!Files.exists(shardFile) || Files.size(shardFile) == 0) {
            return;
        }

        Map<String, ArtifactAccumulator> groups = new HashMap<>();
        try (ShardCodec.ShardReader reader = ShardCodec.openReader(shardFile)) {
            RawArtifact raw;
            while ((raw = reader.next()) != null) {
                String groupId = raw.groupId();
                String artifactId = raw.artifactId();
                String key = groupId + '\t' + artifactId;
                groups.computeIfAbsent(key, ignored -> new ArtifactAccumulator(groupId, artifactId))
                        .add(raw);
            }
        }

        List<AggregatedArtifact> batch = new ArrayList<>(AGGREGATE_BATCH_SIZE);
        for (ArtifactAccumulator accumulator : groups.values()) {
            if (accumulator.isEmpty()) {
                continue;
            }
            batch.add(accumulator.toAggregated());
            if (batch.size() == AGGREGATE_BATCH_SIZE) {
                putBatch(queue, new AggregatedBatch(List.copyOf(batch)), failure);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            putBatch(queue, new AggregatedBatch(List.copyOf(batch)), failure);
        }
    }

    private void writeArtifacts(Connection conn, BlockingQueue<AggregatedBatch> queue, BuildStats stats)
            throws SQLException, InterruptedException {
        String sql = """
                INSERT INTO artifacts
                (ga, group_id, artifact_id, artifact_id_norm, group_id_norm, ga_norm, search_text,
                 latest_version, latest_stable_version, version_count, last_timestamp, versions_blob)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        conn.setAutoCommit(false);
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int pending = 0;
            long sinceCommit = 0;
            while (true) {
                AggregatedBatch batch = queue.take();
                if (batch == POISON) {
                    break;
                }
                for (AggregatedArtifact artifact : batch.items()) {
                    bindArtifact(ps, artifact);
                    ps.addBatch();
                    pending++;
                    sinceCommit++;
                    stats.artifactCount.incrementAndGet();
                    stats.versionCount.addAndGet(artifact.versionCount());
                    if (pending >= 1000) {
                        ps.executeBatch();
                        pending = 0;
                    }
                    if (sinceCommit >= 20_000) {
                        if (pending > 0) {
                            ps.executeBatch();
                            pending = 0;
                        }
                        conn.commit();
                        sinceCommit = 0;
                    }
                }
            }
            if (pending > 0) {
                ps.executeBatch();
            }
            conn.commit();
        }
    }

    private static void bindArtifact(PreparedStatement ps, AggregatedArtifact artifact) throws SQLException {
        String ga = artifact.ga();
        ps.setString(1, ga);
        ps.setString(2, artifact.groupId());
        ps.setString(3, artifact.artifactId());
        ps.setString(4, SearchTextBuilder.normalizeForQuery(artifact.artifactId()));
        ps.setString(5, SearchTextBuilder.normalizeForQuery(artifact.groupId()));
        ps.setString(6, SearchTextBuilder.normalizeForQuery(ga));
        ps.setString(7, artifact.searchText());
        ps.setString(8, artifact.latestVersion());
        ps.setString(9, artifact.latestStableVersion());
        ps.setInt(10, artifact.versionCount());
        ps.setLong(11, artifact.lastTimestamp());
        ps.setBytes(12, artifact.versionsBlob());
    }

    private static void putBatch(
            BlockingQueue<AggregatedBatch> queue,
            AggregatedBatch batch,
            AtomicReference<Throwable> failure
    ) throws InterruptedException {
        while (failure.get() == null) {
            if (queue.offer(batch, 1, TimeUnit.SECONDS)) {
                return;
            }
        }
    }

    private void configureBuildConnection(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA page_size = 4096");
            stmt.execute("PRAGMA journal_mode = OFF");
            stmt.execute("PRAGMA synchronous = OFF");
            stmt.execute("PRAGMA temp_store = MEMORY");
            stmt.execute("PRAGMA locking_mode = EXCLUSIVE");
            stmt.execute("PRAGMA cache_size = -65536");
        }
    }

    private void initBaseSchema(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("""
                    CREATE TABLE meta (
                        key TEXT PRIMARY KEY,
                        value TEXT NOT NULL
                    )
                    """);
            stmt.execute("""
                    CREATE TABLE artifacts (
                        id INTEGER PRIMARY KEY,
                        ga TEXT NOT NULL UNIQUE,
                        group_id TEXT NOT NULL,
                        artifact_id TEXT NOT NULL,
                        artifact_id_norm TEXT NOT NULL,
                        group_id_norm TEXT NOT NULL,
                        ga_norm TEXT NOT NULL,
                        search_text TEXT NOT NULL,
                        latest_version TEXT,
                        latest_stable_version TEXT,
                        version_count INTEGER NOT NULL DEFAULT 0,
                        last_timestamp INTEGER,
                        versions_blob BLOB NOT NULL
                    )
                    """);
            stmt.execute("INSERT INTO meta(key, value) VALUES ('schema_version', '" + SCHEMA_VERSION + "')");
            stmt.execute("INSERT INTO meta(key, value) VALUES ('blob_format', '" + VersionBlobCodec.FORMAT + "')");
        }
    }

    private void createSearchStructures(Connection conn, boolean enableTrigram) throws SQLException {
        LOG.info("Phase 3: Creating indexes and FTS structures...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE INDEX idx_artifacts_group_artifact ON artifacts(group_id, artifact_id)");
            stmt.execute("CREATE INDEX idx_artifacts_artifact_id ON artifacts(artifact_id)");
            stmt.execute("CREATE INDEX idx_artifacts_artifact_id_norm ON artifacts(artifact_id_norm)");
            stmt.execute("CREATE INDEX idx_artifacts_ga_norm ON artifacts(ga_norm)");
            stmt.execute("""
                    CREATE VIRTUAL TABLE artifact_fts USING fts5(
                        ga, group_id, artifact_id, search_text,
                        content='artifacts', content_rowid='id',
                        tokenize="unicode61 tokenchars '.-_:'",
                        prefix='2 3 4'
                    )
                    """);
            stmt.execute("""
                    INSERT INTO artifact_fts(rowid, ga, group_id, artifact_id, search_text)
                    SELECT id, ga, group_id, artifact_id, search_text FROM artifacts
                    """);
            if (enableTrigram) {
                stmt.execute("""
                        CREATE VIRTUAL TABLE artifact_trigram USING fts5(
                            ga, group_id, artifact_id, search_text,
                            content='artifacts', content_rowid='id',
                            tokenize='trigram'
                        )
                        """);
                stmt.execute("""
                        INSERT INTO artifact_trigram(rowid, ga, group_id, artifact_id, search_text)
                        SELECT id, ga, group_id, artifact_id, search_text FROM artifacts
                        """);
            }
        }
        conn.commit();
    }

    private void finishDatabase(Connection conn) throws SQLException {
        LOG.info("Phase 4: ANALYZE + optimize...");
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ANALYZE");
            stmt.execute("PRAGMA optimize");
        }
        conn.commit();
    }

    private void writeMetadata(
            Path workDir,
            BuildOptions options,
            String sourceSha256,
            long sourceSizeBytes,
            long sourceRecords,
            long validRecords,
            BuildStats buildStats,
            long elapsed,
            long dbSize
    ) throws IOException {
        Path metadata = workDir.resolve("metadata.json");
        try (BufferedWriter writer = Files.newBufferedWriter(metadata, StandardCharsets.UTF_8)) {
            writer.write("{\n");
            writeJsonField(writer, "schemaVersion", SCHEMA_VERSION, true);
            writeJsonField(writer, "blobFormat", VersionBlobCodec.FORMAT, true);
            writeJsonField(writer, "artifactCount", Long.toString(buildStats.artifactCount.get()), true);
            writeJsonField(writer, "versionCount", Long.toString(buildStats.versionCount.get()), true);
            writeJsonField(writer, "sourceRecordCount", Long.toString(sourceRecords), true);
            writeJsonField(writer, "validSourceRecordCount", Long.toString(validRecords), true);
            writeJsonField(writer, "buildTimeMs", Long.toString(elapsed), true);
            writeJsonField(writer, "enableTrigram", Boolean.toString(options.enableTrigram()), true);
            writeJsonField(writer, "shards", Integer.toString(options.shardCount()), true);
            writeJsonField(writer, "workers", Integer.toString(options.workers()), true);
            writeJsonField(writer, "dbSizeBytes", Long.toString(dbSize), true);
            writeJsonField(writer, "source", options.sourceGzPath().toString(), true);
            writeJsonField(writer, "sourceSha256", sourceSha256, true);
            writeJsonField(writer, "sourceSizeBytes", Long.toString(sourceSizeBytes), true);
            writeJsonField(writer, "builtAt", Instant.now().toString(), false);
            writer.write("}\n");
        }
    }

    private static void writeJsonField(BufferedWriter writer, String key, String value, boolean comma) throws IOException {
        writer.write("  \"");
        writer.write(escapeJson(key));
        writer.write("\": ");
        if (isJsonLiteral(value)) {
            writer.write(value);
        } else {
            writer.write('"');
            writer.write(escapeJson(value));
            writer.write('"');
        }
        if (comma) {
            writer.write(',');
        }
        writer.write('\n');
    }

    private static boolean isJsonLiteral(String value) {
        if ("true".equals(value) || "false".equals(value) || "null".equals(value)) {
            return true;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < '0' || c > '9') && c != '-') {
                return false;
            }
        }
        return !value.isEmpty();
    }

    private static String escapeJson(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static int shardIndex(String groupId, String artifactId, int shardCount) {
        int h = 31 * groupId.hashCode() + artifactId.hashCode();
        h ^= h >>> 16;
        return h & (shardCount - 1);
    }

    private static void atomicSwitch(Path workDir, Path outputDir) throws IOException {
        Path backupDir = outputDir.resolveSibling(outputDir.getFileName() + ".bak");
        deleteRecursively(backupDir);
        if (Files.exists(outputDir)) {
            Files.move(outputDir, backupDir, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(workDir, outputDir, StandardCopyOption.REPLACE_EXISTING);
            deleteRecursively(backupDir);
        } catch (IOException e) {
            deleteRecursively(outputDir);
            if (Files.exists(backupDir)) {
                Files.move(backupDir, outputDir, StandardCopyOption.REPLACE_EXISTING);
            }
            throw e;
        }
    }

    static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            List<Path> paths = stream.sorted(Comparator.reverseOrder()).toList();
            for (Path p : paths) {
                Files.deleteIfExists(p);
            }
        }
    }

    private static String sha256Hex(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
        try (DigestInputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] hash) {
        StringBuilder out = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            out.append(Character.forDigit((b >>> 4) & 0x0F, 16));
            out.append(Character.forDigit(b & 0x0F, 16));
        }
        return out.toString();
    }

    private record ShardStats(long sourceRecords, long validRecords) {
    }

    private record AggregatedBatch(List<AggregatedArtifact> items) {
    }

    private static final class BuildStats {
        private final AtomicLong artifactCount = new AtomicLong();
        private final AtomicLong versionCount = new AtomicLong();
    }
}
