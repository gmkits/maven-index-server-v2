package com.example.mavenindex.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionStabilityTest {
    @Test
    void detectsUnstableVersions() {
        assertFalse(VersionStability.isStable("1.0.0-SNAPSHOT"));
        assertFalse(VersionStability.isStable("2.0.0-rc1"));
        assertFalse(VersionStability.isStable("3.0.0-M2"));
    }

    @Test
    void acceptsStableVersions() {
        assertTrue(VersionStability.isStable("1.2.3"));
        assertTrue(VersionStability.isStable("2026.05.29"));
    }
}
