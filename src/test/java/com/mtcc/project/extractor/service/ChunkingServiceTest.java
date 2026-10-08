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
import org.mockito.Mockito;
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
        when(chunkingProperties.getProjectTreeMaxDepth()).thenReturn(3);
        when(chunkingProperties.getSummaryMaxModules()).thenReturn(40);
        when(chunkingProperties.getSummaryModuleMaxDepth()).thenReturn(3);
        when(chunkingProperties.getSummaryMaxInputChars()).thenReturn(12000);
        when(chunkingProperties.getSecretsRedactionEnabled()).thenReturn(true);

        when(agentService.analyzeProjectStructure(anyString())).thenReturn(Mono.just("A small sample project."));
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
    void chunkProject_ShouldEmitTheProjectStructureAnalysis() throws IOException {
        final List<InformationChunk> chunks = chunkProject(file("src/Main.java", "class Main {}"));

        final List<InformationChunk> structure = ofType(chunks, InformationType.PROJECT_STRUCTURE);
        assertEquals(1, structure.size());
        assertEquals("A small sample project.", structure.get(0).getText());
        assertEquals(PROJECT_NAME, structure.get(0).getProjectName());
        verify(agentService).analyzeProjectStructure(Mockito.contains("Main.java"));
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
        when(agentService.summarizeProject(anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("model not available")));

        final List<InformationChunk> chunks = chunkProject(rootReadme);

        assertTrue(ofType(chunks, InformationType.PROJECT_SUMMARY).isEmpty());
        assertEquals(1, ofType(chunks, InformationType.PROJECT_STRUCTURE).size());
    }
}
