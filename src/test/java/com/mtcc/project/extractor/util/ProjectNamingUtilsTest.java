package com.mtcc.project.extractor.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectNamingUtilsTest {

    private static final String REPOSITORY_URL = "https://github.com/spring-projects/spring-ai.git";

    @Test
    void urlHelpers_ShouldDeriveTheProjectNameTheWikiNameAndTheWikiUrl() {
        assertEquals("spring-ai", ProjectNamingUtils.urltoProjectName.apply(REPOSITORY_URL));
        assertEquals("spring-ai", ProjectNamingUtils.urltoProjectName.apply("https://github.com/spring-projects/spring-ai"));
        assertEquals("spring-ai_wiki", ProjectNamingUtils.urlToProjectWikiName.apply(REPOSITORY_URL));
        assertEquals("https://github.com/spring-projects/spring-ai.wiki.git", ProjectNamingUtils.toWikiUrl.apply(REPOSITORY_URL));
    }

    @Test
    void isValidProjectName_ShouldAcceptPlainNames() {
        for (final String projectName : List.of("spring-ai", "spring.ai", "spring_ai", "Project1", ".github")) {
            assertTrue(ProjectNamingUtils.isValidProjectName(projectName), "Should accept: '" + projectName + "'");
        }
    }

    @Test
    void isValidProjectName_ShouldRejectEmptyNamesDotsAndNamesWithOtherCharacters() {
        for (final String projectName : List.of("", " ", ".", "..", "a b", "a/b", "a\\b")) {
            assertFalse(ProjectNamingUtils.isValidProjectName(projectName), "Should reject: '" + projectName + "'");
        }
        assertFalse(ProjectNamingUtils.isValidProjectName(null));
    }
}
