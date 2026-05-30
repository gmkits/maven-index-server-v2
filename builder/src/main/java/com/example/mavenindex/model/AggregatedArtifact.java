package com.example.mavenindex.model;

public record AggregatedArtifact(
        String groupId,
        String artifactId,
        String latestVersion,
        String latestStableVersion,
        int versionCount,
        long lastTimestamp,
        String searchText,
        byte[] versionsBlob
) {
    public String ga() {
        return groupId + ":" + artifactId;
    }
}
