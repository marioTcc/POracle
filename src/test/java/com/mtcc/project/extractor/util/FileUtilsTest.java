package com.mtcc.project.extractor.util;

import com.mtcc.project.extractor.entity.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilsTest {

    private final Path projectPath = Paths.get("target", "projects", "spring-ai");
    private final Path wikiPath = Paths.get("target", "projects", "spring-ai_wiki");

    private final Project project = Project.builder()
            .projectName("spring-ai")
            .projectPath(projectPath)
            .projectRepoWikiPath(wikiPath)
            .build();

    @Test
    void getRelativePath_ShouldBeRelativeToTheProjectFolderWithForwardSlashes() {
        assertEquals("models/openai/src/OpenAiApi.java", FileUtils.getRelativePath(project,
                projectPath.resolve("models").resolve("openai").resolve("src").resolve("OpenAiApi.java")));
    }

    @Test
    void getRelativePath_ShouldPrefixWikiFiles() {
        final Path wikiFile = wikiPath.resolve("Home.md");

        assertEquals("wiki/Home.md", FileUtils.getRelativePath(project, wikiFile));
        assertTrue(FileUtils.isWikiFile(project, wikiFile));
        assertFalse(FileUtils.isWikiFile(project, projectPath.resolve("README.md")));
    }

    @Test
    void getRelativePath_ShouldFallBackToTheFileName_WhenTheFileIsOutsideTheProject() {
        assertEquals("notes.md", FileUtils.getRelativePath(project, Paths.get("elsewhere", "notes.md")));
        assertEquals("notes.md", FileUtils.getRelativePath(Project.builder().projectName("empty").build(), Paths.get("notes.md")));
    }

    @Test
    void toUnixPath_ShouldUseForwardSlashes() {
        assertEquals("docs/guide.md", FileUtils.toUnixPath(Paths.get("docs", "guide.md")));
    }

    @Test
    void getLowerCaseExtension_ShouldReturnTheLowerCaseExtensionOfTheFileName() {
        assertEquals("java", FileUtils.getLowerCaseExtension(Path.of("src", "Main.java")));
        assertEquals("md", FileUtils.getLowerCaseExtension(Path.of("README.MD")));
        assertEquals("gz", FileUtils.getLowerCaseExtension(Path.of("archive.tar.gz")));
        assertEquals("gitignore", FileUtils.getLowerCaseExtension(Path.of(".gitignore")));
        assertEquals("", FileUtils.getLowerCaseExtension(Path.of("some.folder", "Makefile")));
    }
}
