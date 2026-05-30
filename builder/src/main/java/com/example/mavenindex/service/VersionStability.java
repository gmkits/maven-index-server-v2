package com.example.mavenindex.service;

import java.util.Locale;
import java.util.regex.Pattern;

public final class VersionStability {
    private static final String[] UNSTABLE_KEYWORDS = {
            "snapshot", "alpha", "beta", "rc", "cr", "milestone", "preview", "ea"
    };
    private static final Pattern MILESTONE_PATTERN = Pattern.compile("(?i)(?:^|[.\\-])M\\d+(?:[.\\-]|$)");

    private VersionStability() {
    }

    public static boolean isStable(String version) {
        String lower = version.toLowerCase(Locale.ROOT);
        for (String keyword : UNSTABLE_KEYWORDS) {
            if (lower.contains(keyword)) {
                return false;
            }
        }
        return !MILESTONE_PATTERN.matcher(version).find();
    }
}
