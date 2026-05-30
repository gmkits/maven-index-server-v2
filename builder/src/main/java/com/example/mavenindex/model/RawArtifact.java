package com.example.mavenindex.model;

public record RawArtifact(
        String groupId,
        String artifactId,
        String version,
        String packaging,
        Long timestamp
) {
}
