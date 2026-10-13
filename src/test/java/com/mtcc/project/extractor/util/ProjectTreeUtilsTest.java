package com.mtcc.project.extractor.util;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.entity.ProjectFolder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectTreeUtilsTest {

    private List<ProjectFolder> foldersOf(final String... filePaths) {
        return ProjectTreeUtils.getFoldersDeepestFirst(Stream.of(filePaths)
                .map(filePath -> InformationChunk.buildChunk("sample", filePath, "java", InformationType.CODE,
                        "outline of " + filePath, "", "code-outline"))
                .toList());
    }

    private List<String> paths(final List<ProjectFolder> folders) {
        return folders.stream().map(ProjectFolder::getPath).toList();
    }

    private List<String> filesOf(final ProjectFolder folder) {
        return folder.getFiles().stream().map(InformationChunk::getFilePath).toList();
    }

    @Test
    void getFoldersDeepestFirst_ShouldReturnEveryFolderBeforeTheFolderThatContainsIt() {
        final List<ProjectFolder> folders = foldersOf("README.md", "core/Guide.md", "core/src/Main.java", "docs/index.md");

        assertEquals(List.of("core/src", "core", "docs", ""), paths(folders));
        assertEquals(List.of("core/src"), folders.get(1).getSubfolders());
        assertEquals(List.of("core/Guide.md"), filesOf(folders.get(1)));
        assertEquals(List.of("core", "docs"), folders.get(3).getSubfolders());
        assertEquals(List.of("README.md"), filesOf(folders.get(3)));
    }

    @Test
    void getFoldersDeepestFirst_ShouldSkipTheFoldersThatOnlyContainOneFolder() {
        final List<ProjectFolder> folders = foldersOf(
                "src/main/java/com/sample/Main.java",
                "src/main/java/com/sample/util/Strings.java",
                "src/main/resources/application.yml");

        assertEquals(List.of("src/main/java/com/sample/util", "src/main/java/com/sample", "src/main/resources", "src/main", ""),
                paths(folders));
        assertEquals(List.of("src/main/java/com/sample", "src/main/resources"), folders.get(3).getSubfolders());
        assertEquals(List.of("src/main"), folders.get(4).getSubfolders());
    }

    @Test
    void getFoldersDeepestFirst_ShouldKeepTheRoot_EvenWhenItOnlyContainsOneFolder() {
        final List<ProjectFolder> folders = foldersOf("src/Main.java");

        assertEquals(List.of("src", ""), paths(folders));
        assertEquals(List.of("src"), folders.get(1).getSubfolders());
        assertTrue(folders.get(1).getFiles().isEmpty());
    }

    @Test
    void getFoldersDeepestFirst_ShouldNotConfuseFoldersWhoseNamesStartAlike() {
        final List<ProjectFolder> folders = foldersOf("core/Main.java", "core-utils/Strings.java");

        assertEquals(List.of("core", "core-utils", ""), paths(folders));
        assertEquals(List.of("core/Main.java"), filesOf(folders.get(0)));
    }

    @Test
    void getFoldersDeepestFirst_ShouldBeEmpty_WhenTheProjectHasNoFiles() {
        assertTrue(ProjectTreeUtils.getFoldersDeepestFirst(List.of()).isEmpty());
    }
}
