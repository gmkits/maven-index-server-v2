package com.example.mavenindex.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record BuildOptions(
        Path sourceGzPath,
        Path outputDir,
        boolean enableTrigram,
        long maxRecords,
        int shardCount,
        int workers
) {
    public static BuildOptions parse(String[] args) {
        Path source = envPath("SOURCE_GZ", Path.of("nexus-maven-repository-index.gz"));
        Path output = envPath("OUTPUT_DIR", Path.of("/data/maven-index/current"));
        boolean enableTrigram = envBoolean("ENABLE_TRIGRAM", false);
        long maxRecords = envLong("MAX_RECORDS", 0);
        int shardCount = (int) envLong("SHARDS", 512);
        int workers = (int) envLong("WORKERS", Math.min(4, Math.max(1, Runtime.getRuntime().availableProcessors() / 2)));

        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }

            String name;
            String value;
            int eq = arg.indexOf('=');
            if (eq >= 0) {
                name = arg.substring(2, eq);
                value = arg.substring(eq + 1);
            } else {
                name = arg.substring(2);
                if ("enable-trigram".equals(name)) {
                    value = "true";
                } else {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException("Missing value for --" + name);
                    }
                    value = args[++i];
                }
            }

            switch (name) {
                case "source" -> source = Path.of(value);
                case "output" -> output = Path.of(value);
                case "enable-trigram" -> enableTrigram = Boolean.parseBoolean(value);
                case "max-records" -> maxRecords = parseLong(value, "--max-records");
                case "shards" -> shardCount = (int) parseLong(value, "--shards");
                case "workers" -> workers = (int) parseLong(value, "--workers");
                default -> throw new IllegalArgumentException("Unknown option --" + name);
            }
        }

        if (!positional.isEmpty()) {
            source = Path.of(positional.getFirst());
        }
        if (positional.size() > 1) {
            output = Path.of(positional.get(1));
        }
        if (positional.size() > 2) {
            enableTrigram = Boolean.parseBoolean(positional.get(2));
        }
        if (positional.size() > 3) {
            throw new IllegalArgumentException("Too many positional arguments");
        }

        if (shardCount < 1 || Integer.bitCount(shardCount) != 1) {
            throw new IllegalArgumentException("--shards must be a positive power of two");
        }
        if (workers < 1) {
            throw new IllegalArgumentException("--workers must be positive");
        }
        if (maxRecords < 0) {
            throw new IllegalArgumentException("--max-records must be zero or positive");
        }

        return new BuildOptions(
                source.toAbsolutePath().normalize(),
                output.toAbsolutePath().normalize(),
                enableTrigram,
                maxRecords,
                shardCount,
                workers
        );
    }

    private static Path envPath(String name, Path defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Path.of(value);
    }

    private static boolean envBoolean(String name, boolean defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : Boolean.parseBoolean(value);
    }

    private static long envLong(String name, long defaultValue) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? defaultValue : parseLong(value, name);
    }

    private static long parseLong(String value, String name) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be an integer: " + value, e);
        }
    }
}
