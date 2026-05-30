package com.example.mavenindex.model;

public record VersionEntry(
        String version,
        String packaging,
        Long timestamp,
        boolean stable
) {
}
