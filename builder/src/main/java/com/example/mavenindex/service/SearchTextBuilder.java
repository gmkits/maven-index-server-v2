package com.example.mavenindex.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class SearchTextBuilder {
    private SearchTextBuilder() {
    }

    public static String build(String groupId, String artifactId) {
        Set<String> tokens = new LinkedHashSet<>();
        tokens.add(groupId + ":" + artifactId);
        tokens.add(groupId);
        tokens.add(artifactId);

        addSplitTokens(tokens, groupId);
        addSplitTokens(tokens, artifactId);

        String artifactConcat = normalizeForQuery(artifactId);
        if (!artifactConcat.isEmpty()) {
            tokens.add(artifactConcat);
        }
        String groupConcat = normalizeForQuery(groupId);
        if (!groupConcat.isEmpty()) {
            tokens.add(groupConcat);
        }

        return String.join(" ", tokens);
    }

    public static String normalizeForQuery(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (!isSeparator(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static void addSplitTokens(Set<String> tokens, String input) {
        int start = -1;
        for (int i = 0; i <= input.length(); i++) {
            if (i == input.length() || isSeparator(input.charAt(i))) {
                if (start >= 0 && i > start) {
                    tokens.add(input.substring(start, i).toLowerCase(Locale.ROOT));
                }
                start = -1;
            } else if (start < 0) {
                start = i;
            }
        }
    }

    private static boolean isSeparator(char c) {
        return c == '.' || c == '-' || c == '_' || c == ':';
    }
}
