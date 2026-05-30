package com.example.mavenindex.service;

import com.example.mavenindex.model.AggregatedArtifact;
import com.example.mavenindex.model.RawArtifact;
import com.example.mavenindex.model.VersionEntry;
import org.apache.maven.artifact.versioning.ComparableVersion;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ArtifactAccumulator {
    private final String groupId;
    private final String artifactId;
    private final Map<String, VersionEntry> versionsByName = new HashMap<>();

    ArtifactAccumulator(String groupId, String artifactId) {
        this.groupId = groupId;
        this.artifactId = artifactId;
    }

    void add(RawArtifact raw) {
        if (raw.version().isBlank()) {
            return;
        }
        VersionEntry next = new VersionEntry(
                raw.version(),
                raw.packaging(),
                raw.timestamp(),
                VersionStability.isStable(raw.version())
        );
        VersionEntry existing = versionsByName.get(raw.version());
        if (existing == null || compareTimestamp(next.timestamp(), existing.timestamp()) > 0) {
            versionsByName.put(raw.version(), next);
        }
    }

    AggregatedArtifact toAggregated() throws IOException {
        List<VersionEntry> versions = new ArrayList<>(versionsByName.values());
        versions.sort(ArtifactAccumulator::compareVersionsDescending);

        VersionEntry latest = versions.getFirst();
        VersionEntry latestStable = null;
        long lastTimestamp = 0;
        for (VersionEntry entry : versions) {
            long ts = entry.timestamp() == null ? 0L : entry.timestamp();
            if (ts > lastTimestamp) {
                lastTimestamp = ts;
            }
            if (latestStable == null && entry.stable()) {
                latestStable = entry;
            }
        }

        return new AggregatedArtifact(
                groupId,
                artifactId,
                latest.version(),
                latestStable == null ? null : latestStable.version(),
                versions.size(),
                lastTimestamp,
                SearchTextBuilder.build(groupId, artifactId),
                VersionBlobCodec.encode(versions)
        );
    }

    boolean isEmpty() {
        return versionsByName.isEmpty();
    }

    int versionCount() {
        return versionsByName.size();
    }

    private static int compareTimestamp(Long left, Long right) {
        long l = left == null ? 0L : left;
        long r = right == null ? 0L : right;
        return Long.compare(l, r);
    }

    private static int compareVersionsDescending(VersionEntry left, VersionEntry right) {
        int versionCompare = new ComparableVersion(right.version()).compareTo(new ComparableVersion(left.version()));
        if (versionCompare != 0) {
            return versionCompare;
        }
        return Long.compare(
                right.timestamp() == null ? 0L : right.timestamp(),
                left.timestamp() == null ? 0L : left.timestamp()
        );
    }
}
