package com.example.mavenindex;

import com.example.mavenindex.service.BuildOptions;
import com.example.mavenindex.service.BuildOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BuilderMain {
    private static final Logger LOG = LoggerFactory.getLogger(BuilderMain.class);

    private BuilderMain() {
    }

    public static void main(String[] args) throws Exception {
        BuildOptions options = BuildOptions.parse(args);

        LOG.info("=== Maven Index Builder v2 ===");
        LOG.info("Source: {}", options.sourceGzPath());
        LOG.info("Output: {}", options.outputDir());
        LOG.info("Trigram: {}", options.enableTrigram());
        LOG.info("Shards: {}", options.shardCount());
        LOG.info("Workers: {}", options.workers());
        if (options.maxRecords() > 0) {
            LOG.info("Max source records: {}", options.maxRecords());
        }

        new BuildOrchestrator().build(options);
        LOG.info("=== Build Complete ===");
    }
}
