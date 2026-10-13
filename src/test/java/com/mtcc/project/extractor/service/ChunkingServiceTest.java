package com.mtcc.project.extractor.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.project.extractor.util.FileUtils;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.common.security.SecretRedactor;
import com.mtcc.oracle.service.AgentService;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChunkingServiceTest {

    private static final String PROJECT_NAME = "sample-project";

    @TempDir
    private Path tempDir;

    @Mock
    private ChunkingProperties chunkingProperties;
    @Mock
    private IngestionProgress ingestionProgress;
    @Mock
    private AgentService agentService;
    @Mock
    private IIOServiceImpl ioService;
    @Mock
    private IChunkingServiceImpl codeChunkingService;
    @Mock
    private IChunkingServiceImpl textChunkingService;
    @Mock
    private IChunkingServiceImpl wholeFileChunkingService;

    private ChunkingService chunkingService;

    private Project project;

    @BeforeEach
    void setUp() {
        chunkingService = new ChunkingService(chunkingProperties, ingestionProgress,
                new SecretRedactor(), agentService, ioService, codeChunkingService, textChunkingService, wholeFileChunkingService);
        project = Project.builder().projectName(PROJECT_NAME).projectPath(tempDir).build();

        when(chunkingProperties.getCodeFileExtensions()).thenReturn(List.of("java"));
        when(chunkingProperties.getDocsFileExtensions()).thenReturn(List.of("md"));
        when(chunkingProperties.getConfigFileExtensions()).thenReturn(List.of("properties"));
        when(chunkingProperties.getSummaryMaxModules()).thenReturn(40);
        when(chunkingProperties.getSummaryModuleMaxDepth()).thenReturn(3);
        when(chunkingProperties.getSummaryMaxInputChars()).thenReturn(12000);
        when(chunkingProperties.getSecretsRedactionEnabled()).thenReturn(true);

        when(agentService.summarizeFolder(anyString(), anyString(), anyString())).thenReturn(Mono.just("It holds sample files."));
        when(agentService.summarizeProject(anyString(), anyString())).thenReturn(Mono.just("It is a sample."));
        when(agentService.summarizeModule(anyString(), anyString(), anyString())).thenReturn(Mono.just("It is a module."));

        when(ioService.readText(any())).thenAnswer(call -> Mono.fromCallable(() -> Files.readString(call.<Path>getArgument(0))));
        when(codeChunkingService.chunkFile(any(), any(), any())).thenReturn(Flux.empty());
        when(textChunkingService.chunkFile(any(), any(), any())).thenReturn(Flux.empty());
        when(wholeFileChunkingService.chunkFile(any(), any(), any())).thenReturn(Flux.empty());
    }

    private Path file(final String relativePath, final String content) throws IOException {
        final Path file = tempDir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, content);
    }

    private InformationChunk chunk(final Path file, final InformationType type, final String text) {
        return InformationChunk.buildChunk(project.getProjectName(), FileUtils.getRelativePath(project, file), "txt", type,
                text, "context", "test");
    }

    private List<InformationChunk> chunkProject(final Path... files) {
        return chunkingService.chunkProject(project, Flux.just(files)).collectList().block();
    }

    private List<InformationChunk> ofType(final List<InformationChunk> chunks, final InformationType type) {
        return chunks.stream().filter(chunk -> chunk.getInformationType() == type).toList();
    }

    @Test
    void chunkProject_ShouldSendEachFileToTheChunkerOfItsType() throws IOException {
        final Path code = file("src/Main.java", "class Main {}");
        final Path doc = file("docs/guide.md", "A guide.");
        final Path config = file("app.properties", "server.port=8080");
        when(codeChunkingService.chunkFile(project, code, InformationType.CODE))
                .thenReturn(Flux.just(chunk(code, InformationType.CODE, "class Main {}")));
        when(textChunkingService.chunkFile(project, doc, InformationType.DOC))
                .thenReturn(Flux.just(chunk(doc, InformationType.DOC, "A guide.")));
        when(textChunkingService.chunkFile(project, config, InformationType.CONFIG))
                .thenReturn(Flux.just(chunk(config, InformationType.CONFIG, "server.port=8080")));
        when(wholeFileChunkingService.chunkFile(any(), any(), any()))
                .thenReturn(Flux.just(chunk(code, InformationType.CODE, "whole file fallback")));

        final List<InformationChunk> chunks = chunkProject(code, doc, config);

        assertEquals(List.of("class Main {}"), ofType(chunks, InformationType.CODE).stream().map(InformationChunk::getText).toList());
        assertEquals(List.of("A guide."), ofType(chunks, InformationType.DOC).stream().map(InformationChunk::getText).toList());
        assertEquals(List.of("server.port=8080"), ofType(chunks, InformationType.CONFIG).stream().map(InformationChunk::getText).toList());
        assertTrue(chunks.stream().noneMatch(chunk -> "whole file fallback".equals(chunk.getText())),
                "The whole-file fallback must not be used");
        verify(ingestionProgress, times(3)).fileProcessed(PROJECT_NAME);
    }

    @Test
    void chunkProject_ShouldIndexTheWholeFile_WhenItsChunkerEmitsNothing() throws IOException {
        final Path code = file("src/package-info.java", "package sample;");
        when(wholeFileChunkingService.chunkFile(project, code, InformationType.CODE))
                .thenReturn(Flux.just(chunk(code, InformationType.CODE, "package sample;")));

        final List<InformationChunk> chunks = chunkProject(code);

        assertEquals(List.of("package sample;"), ofType(chunks, InformationType.CODE).stream().map(InformationChunk::getText).toList());
    }

    @Test
    void chunkProject_ShouldIndexTheWholeFile_WhenItsChunkerFails() throws IOException {
        final Path code = file("src/Broken.java", "class Broken {");
        when(codeChunkingService.chunkFile(project, code, InformationType.CODE))
                .thenReturn(Flux.error(new IllegalStateException("cannot parse")));
        when(wholeFileChunkingService.chunkFile(project, code, InformationType.CODE))
                .thenReturn(Flux.just(chunk(code, InformationType.CODE, "class Broken {")));

        final List<InformationChunk> chunks = chunkProject(code);

        assertEquals(1, ofType(chunks, InformationType.CODE).size());
        verify(ingestionProgress).fileProcessed(PROJECT_NAME);
    }

    @Test
    void chunkProject_ShouldSkipAFileThatCannotBeRead_AndKeepGoing() throws IOException {
        final Path unreadable = file("docs/unreadable.md", "x");
        final Path readable = file("docs/readable.md", "A readable guide.");
        when(textChunkingService.chunkFile(project, unreadable, InformationType.DOC))
                .thenReturn(Flux.error(new IOException("locked")));
        when(wholeFileChunkingService.chunkFile(project, unreadable, InformationType.DOC))
                .thenReturn(Flux.error(new IOException("locked")));
        when(textChunkingService.chunkFile(project, readable, InformationType.DOC))
                .thenReturn(Flux.just(chunk(readable, InformationType.DOC, "A readable guide.")));

        final List<InformationChunk> chunks = chunkProject(unreadable, readable);

        assertEquals(List.of("A readable guide."), ofType(chunks, InformationType.DOC).stream().map(InformationChunk::getText).toList());
        verify(ingestionProgress).fileSkipped(PROJECT_NAME);
        verify(ingestionProgress).fileProcessed(PROJECT_NAME);
    }

    @Test
    void chunkProject_ShouldRedactSecretsAndCountThem() throws IOException {
        final Path config = file("app.properties", "db.password=s3cr3t");
        when(textChunkingService.chunkFile(project, config, InformationType.CONFIG))
                .thenReturn(Flux.just(chunk(config, InformationType.CONFIG, "db.password=s3cr3t")));

        final List<InformationChunk> chunks = chunkProject(config);

        assertEquals("db.password=[REDACTED]", ofType(chunks, InformationType.CONFIG).get(0).getText());
        verify(ingestionProgress).secretsRedacted(PROJECT_NAME, 1);
    }

    @Test
    void chunkProject_ShouldKeepSecrets_WhenRedactionIsDisabled() throws IOException {
        when(chunkingProperties.getSecretsRedactionEnabled()).thenReturn(false);
        final Path config = file("app.properties", "db.password=s3cr3t");
        when(textChunkingService.chunkFile(project, config, InformationType.CONFIG))
                .thenReturn(Flux.just(chunk(config, InformationType.CONFIG, "db.password=s3cr3t")));

        final List<InformationChunk> chunks = chunkProject(config);

        assertEquals("db.password=s3cr3t", ofType(chunks, InformationType.CONFIG).get(0).getText());
    }

    @Test
    void chunkProject_ShouldEmitOneOverviewPerFolder_FromTheDeepestToTheRoot() throws IOException {
        final Path main = file("src/main/java/sample/Main.java", "class Main {}");
        final Path util = file("src/main/java/sample/util/Strings.java", "class Strings {}");
        final Path readme = file("README.md", "Sample project documentation.");
        when(codeChunkingService.chunkFile(project, main, InformationType.CODE))
                .thenReturn(Flux.just(chunk(main, InformationType.CODE, "class Main\nMethods: main")));
        when(codeChunkingService.chunkFile(project, util, InformationType.CODE))
                .thenReturn(Flux.just(chunk(util, InformationType.CODE, "class Strings\nMethods: isBlank")));
        when(textChunkingService.chunkFile(project, readme, InformationType.DOC))
                .thenReturn(Flux.just(chunk(readme, InformationType.DOC, "Sample project documentation.")));
        when(agentService.summarizeFolder(PROJECT_NAME, "src/main/java/sample/util", "File Strings.java\nclass Strings\nMethods: isBlank"))
                .thenReturn(Mono.just("String helpers."));

        final List<InformationChunk> folders = ofType(chunkProject(main, util, readme), InformationType.FOLDER_SUMMARY);

        assertEquals(List.of("src/main/java/sample/util", "src/main/java/sample", ""),
                folders.stream().map(InformationChunk::getFilePath).toList());
        assertEquals("""
                Overview of folder src/main/java/sample/util of project sample-project: String helpers.

                Contents of folder src/main/java/sample/util of project sample-project:
                - src/main/java/sample/util/Strings.java""", folders.get(0).getText());
        assertEquals("folder src/main/java/sample/util", folders.get(0).getContext());
        assertEquals("""
                Overview of folder / of project sample-project: It holds sample files.

                Contents of folder / of project sample-project:
                - src/main/java/sample/
                - README.md""", folders.get(2).getText());
        assertEquals("project structure", folders.get(2).getContext());
        verify(agentService).summarizeFolder(PROJECT_NAME, "src/main/java/sample",
                "Folder src/main/java/sample/util/\nString helpers.\n\nFile Main.java\nclass Main\nMethods: main");
    }

    @Test
    void chunkProject_ShouldStillListTheContentsOfAFolder_WhenItsSummaryFails() throws IOException {
        final Path code = file("src/Main.java", "class Main {}");
        when(codeChunkingService.chunkFile(project, code, InformationType.CODE))
                .thenReturn(Flux.just(chunk(code, InformationType.CODE, "class Main {}")));
        when(agentService.summarizeFolder(anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("model not available")));

        final List<InformationChunk> folders = ofType(chunkProject(code), InformationType.FOLDER_SUMMARY);

        assertEquals(List.of("Contents of folder src of project sample-project:\n- src/Main.java",
                        "Contents of folder / of project sample-project:\n- src/"),
                folders.stream().map(InformationChunk::getText).toList());
    }

    @Test
    void chunkProject_ShouldNotSendSecretsToTheFolderSummary() throws IOException {
        final Path config = file("app.properties", "db.password=s3cr3t");
        when(textChunkingService.chunkFile(project, config, InformationType.CONFIG))
                .thenReturn(Flux.just(chunk(config, InformationType.CONFIG, "db.password=s3cr3t")));

        chunkProject(config);

        verify(agentService).summarizeFolder(PROJECT_NAME, "/", "File app.properties\ndb.password=[REDACTED]");
    }

    @Test
    void chunkProject_ShouldTellWhereEachFileCanBeReadAgain() throws IOException {
        project = project.toBuilder().projectUrl("https://github.com/sample/sample-project.git").revision("4f2c9d1")
                .projectRepoWikiPath(tempDir.resolve("wiki-clone")).build();
        final Path code = file("src/Main.java", "class Main {}");
        final Path wikiPage = file("wiki-clone/Home.md", "Welcome.");
        when(codeChunkingService.chunkFile(project, code, InformationType.CODE))
                .thenReturn(Flux.just(chunk(code, InformationType.CODE, "class Main {}")));
        when(textChunkingService.chunkFile(project, wikiPage, InformationType.DOC))
                .thenReturn(Flux.just(chunk(wikiPage, InformationType.DOC, "Welcome.")));

        final List<InformationChunk> chunks = chunkProject(code, wikiPage);

        final InformationChunk codeOutline = ofType(chunks, InformationType.CODE).get(0);
        assertEquals(code.toAbsolutePath().normalize().toString(), codeOutline.getLocalPath());
        assertEquals("https://github.com/sample/sample-project.git", codeOutline.getRepositoryUrl());
        assertEquals("4f2c9d1", codeOutline.getRevision());
        final InformationChunk wikiOutline = ofType(chunks, InformationType.DOC).get(0);
        assertEquals("wiki/Home.md", wikiOutline.getFilePath());
        assertEquals(wikiPage.toAbsolutePath().normalize().toString(), wikiOutline.getLocalPath());
        assertNull(wikiOutline.getRepositoryUrl());
    }

    @Test
    void chunkProject_ShouldSummarizeTheRootReadmeAsProjectAndNestedReadmesAsModules() throws IOException {
        final Path rootReadme = file("README.md", "Sample project documentation.");
        final Path moduleReadme = file("modules/core/README.md", "Core module documentation.");

        final List<InformationChunk> chunks = chunkProject(rootReadme, moduleReadme);

        final List<InformationChunk> projectSummaries = ofType(chunks, InformationType.PROJECT_SUMMARY);
        final List<InformationChunk> moduleSummaries = ofType(chunks, InformationType.MODULE_SUMMARY);
        assertEquals(List.of("Overview of project sample-project: It is a sample."),
                projectSummaries.stream().map(InformationChunk::getText).toList());
        assertEquals(List.of("Overview of module modules/core of project sample-project: It is a module."),
                moduleSummaries.stream().map(InformationChunk::getText).toList());
        assertEquals("modules/core/README.md", moduleSummaries.get(0).getFilePath());
        verify(agentService).summarizeProject(PROJECT_NAME, "Sample project documentation.");
        verify(agentService).summarizeModule(PROJECT_NAME, "modules/core", "Core module documentation.");
    }

    @Test
    void chunkProject_ShouldNotSummarizeReadmesDeeperThanTheConfiguredDepth() throws IOException {
        when(chunkingProperties.getSummaryModuleMaxDepth()).thenReturn(1);
        final Path shallowReadme = file("core/README.md", "Core module documentation.");
        final Path deepReadme = file("core/src/test/README.md", "Test notes.");

        final List<InformationChunk> chunks = chunkProject(shallowReadme, deepReadme);

        assertEquals(1, ofType(chunks, InformationType.MODULE_SUMMARY).size());
        verify(agentService).summarizeModule(eq(PROJECT_NAME), eq("core"), anyString());
    }

    @Test
    void chunkProject_ShouldKeepGoing_WhenASummaryFails() throws IOException {
        final Path rootReadme = file("README.md", "Sample project documentation.");
        when(textChunkingService.chunkFile(project, rootReadme, InformationType.DOC))
                .thenReturn(Flux.just(chunk(rootReadme, InformationType.DOC, "Sample project documentation.")));
        when(agentService.summarizeProject(anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("model not available")));

        final List<InformationChunk> chunks = chunkProject(rootReadme);

        assertTrue(ofType(chunks, InformationType.PROJECT_SUMMARY).isEmpty());
        assertEquals(1, ofType(chunks, InformationType.FOLDER_SUMMARY).size());
    }
}
