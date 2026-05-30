package com.example.mavenindex.service;

import com.example.mavenindex.model.RawArtifact;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtifactAccumulatorTest {
    @Test
    void choosesLatestByMavenVersionNotTimestamp() throws Exception {
        ArtifactAccumulator accumulator = new ArtifactAccumulator("junit", "junit");
        accumulator.add(new RawArtifact("junit", "junit", "3.7", "jar", 5000L));
        accumulator.add(new RawArtifact("junit", "junit", "4.13.2", "jar", 1000L));

        var artifact = accumulator.toAggregated();
        assertEquals("4.13.2", artifact.latestVersion());
        assertEquals("4.13.2", artifact.latestStableVersion());
        assertEquals(5000L, artifact.lastTimestamp());
    }
}
