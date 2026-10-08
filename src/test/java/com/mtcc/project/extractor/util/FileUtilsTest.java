package com.mtcc.project.extractor.util;

import com.mtcc.project.extractor.entity.Project;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileUtilsTest {

    private static final List<String> FILE_PATHS = List.of(
            "core/src/Util.java",
            "README.md",
            "core/src/Main.java",
            "core-utils/Strings.java",
            "core/Guide.md",
            "wiki/Home.md");

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
    void getProjectFilesTree_ShouldRenderFoldersAndFilesSortedAndIndented() {
        assertEquals("""
                README.md
                core/
                 Guide.md
                 src/
                  Main.java
                  Util.java
                core-utils/
                 Strings.java
                wiki/
                 Home.md""", FileUtils.getProjectFilesTree(FILE_PATHS, 5));
    }

    @Test
    void getProjectFilesTree_ShouldCountTheFilesOfTheFoldersItDoesNotOpen() {
        assertEquals("""
                README.md
                core/
                 Guide.md
                 src/ (2 files)
                core-utils/
                 Strings.java
                wiki/
                 Home.md""", FileUtils.getProjectFilesTree(FILE_PATHS, 2));

        assertEquals("""
                README.md
                core/ (3 files)
                core-utils/ (1 file)
                wiki/ (1 file)""", FileUtils.getProjectFilesTree(FILE_PATHS, 1));
    }

    @Test
    void getProjectFilesTree_ShouldBeEmpty_WhenTheProjectHasNoFiles() {
        assertEquals("", FileUtils.getProjectFilesTree(List.of(), 3));
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
