package com.example.mavenindex.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchTextBuilderTest {
    @Test
    void normalizesSeparatorsLikeGoServer() {
        assertEquals("orgspringframeworkbootspringbootstarterweb",
                SearchTextBuilder.normalizeForQuery("org.springframework.boot:spring-boot_starter-web"));
    }

    @Test
    void buildsFullAndSplitTokens() {
        String text = SearchTextBuilder.build("org.springframework.boot", "spring-boot-starter-web");
        assertTrue(text.contains("org.springframework.boot:spring-boot-starter-web"));
        assertTrue(text.contains("spring"));
        assertTrue(text.contains("springbootstarterweb"));
    }
}
