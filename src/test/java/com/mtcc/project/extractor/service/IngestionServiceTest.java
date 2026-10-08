package com.mtcc.project.extractor.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.project.extractor.config.IngestionProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.exception.IngestionAlreadyRunningException;
import com.mtcc.project.extractor.exception.InvalidIngestionSourceException;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.project.extractor.serviceimpl.interfaces.IGitServiceImpl;
import com.mtcc.rag.service.RagService;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionServiceTest {

    private static final int TIMEOUT_MILLIS = 5000;
    private static final String SPRING_AI_URL = "https://github.com/spring-projects/spring-ai.git";
    private static final String SPRING_SECURITY_URL = "https://github.com/spring-projects/spring-security.git";
    private static final String INDEXING_DISABLED = "indexing disabled";
    private static final String INDEXING_ENABLED = "indexing enabled";
    private static final String CHUNKS_INGESTED = "chunks ingested";
    private static final String OLD_CHUNKS_DELETED = "old chunks deleted";

    @TempDir
    private Path tempDir;

    @Mock
    private IGitServiceImpl gitService;
    @Mock
    private ChunkingService chunkingService;
    @Mock
    private RagService ragService;
    @Mock
    private IngestionProgress ingestionProgress;
    @Mock
    private IngestionProperties ingestionProperties;
    @Mock
    private IIOServiceImpl ioService;
    @InjectMocks
    private IngestionService ingestionService;

    private final List<String> events = new CopyOnWriteArrayList<>();

    private final InformationChunk chunk = InformationChunk.buildChunk(
            "sample", "README.md", "MD", InformationType.DOC, "Some documentation.", "README.md", "text");

    @BeforeEach
    void setUp() {
        when(ioService.getExistingFolder(anyString())).thenAnswer(call ->
                Mono.just(Path.of(call.<String>getArgument(0)).toAbsolutePath().normalize()));
        when(ingestionProperties.getBatchSize()).thenReturn(75);
        when(ingestionProperties.getBatchTimeoutSeconds()).thenReturn(5);
        when(ingestionProperties.getMaxBufferedBatches()).thenReturn(5000);
        when(ragService.prepareCollection()).thenReturn(Mono.empty());
        when(ragService.deleteOldIngestionChunks(anyString(), anyString()))
                .thenReturn(Mono.fromRunnable(() -> events.add(OLD_CHUNKS_DELETED)));
        when(ragService.disableIndexing()).thenReturn(Mono.fromRunnable(() -> events.add(INDEXING_DISABLED)));
        when(ragService.enableIndexing()).thenReturn(Mono.fromRunnable(() -> events.add(INDEXING_ENABLED)));
        when(ragService.ingestChunksBatch(anyList())).thenAnswer(call -> Flux.fromIterable(call.<List<InformationChunk>>getArgument(0))
                .doOnSubscribe(subscription -> events.add(CHUNKS_INGESTED)));
        when(ioService.getAllFiles(anyString())).thenAnswer(call ->
                Flux.just(Path.of(call.<String>getArgument(0)).resolve("README.md")));
        when(chunkingService.chunkProject(any(), any())).thenReturn(Flux.just(chunk));
        when(gitService.cloneRepository(anyString())).thenAnswer(call -> Mono.just(repositoryPath(call.getArgument(0))));
        when(gitService.cloneWiki(anyString())).thenAnswer(call -> Mono.just(wikiPath(call.getArgument(0))));
    }

    private Path repositoryPath(final String repositoryUrl) {
        return tempDir.resolve(StringUtils.substringBetween(repositoryUrl, "spring-projects/", ".git"));
    }

    private Path wikiPath(final String repositoryUrl) {
        return tempDir.resolve(StringUtils.substringBetween(repositoryUrl, "spring-projects/", ".git") + "_wiki");
    }

    private Project chunkedProject() {
        final ArgumentCaptor<Project> project = ArgumentCaptor.forClass(Project.class);
        verify(chunkingService, timeout(TIMEOUT_MILLIS)).chunkProject(project.capture(), any());
        return project.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<Path> chunkedFiles() {
        final ArgumentCaptor<Flux<Path>> files = ArgumentCaptor.forClass(Flux.class);
        verify(chunkingService, timeout(TIMEOUT_MILLIS)).chunkProject(any(), files.capture());
        return files.getValue().collectList().block();
    }

    @Test
    void startIngestFromPath_ShouldIngestTheFolderAsAProjectNamedAfterIt() {
        ingestionService.startIngestFromPath(tempDir.toString()).block();

        final Project project = chunkedProject();
        verify(ragService, timeout(TIMEOUT_MILLIS)).ingestChunksBatch(anyList());

        assertEquals(tempDir.getFileName().toString(), project.getProjectName());
        assertEquals(tempDir.toAbsolutePath().normalize(), project.getProjectPath());
        assertNull(project.getProjectRepoWikiPath());
        assertEquals(List.of(tempDir.toAbsolutePath().normalize().resolve("README.md")), chunkedFiles());
        verify(gitService, never()).cloneRepository(anyString());
        verify(gitService, never()).cloneWiki(anyString());
    }

    @Test
    void startIngestFromPath_ShouldListTheFilesOnlyOnce_EvenIfTheChunkerReadsThemSeveralTimes() {
        final AtomicInteger listings = new AtomicInteger();
        when(ioService.getAllFiles(anyString())).thenReturn(Flux.defer(() -> {
            listings.incrementAndGet();
            return Flux.just(tempDir.resolve("README.md"));
        }));
        when(chunkingService.chunkProject(any(), any())).thenAnswer(call -> {
            final Flux<Path> files = call.getArgument(1);
            return files.thenMany(files).thenMany(Flux.just(chunk));
        });

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        assertEquals(1, listings.get());
    }

    @Test
    void startIngestFromPath_ShouldDisableIndexingBeforeAndEnableItAfterTheIngestion() {
        ingestionService.startIngestFromPath(tempDir.toString()).block();

        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        assertEquals(List.of(INDEXING_DISABLED, CHUNKS_INGESTED, OLD_CHUNKS_DELETED, INDEXING_ENABLED), events);
    }

    @Test
    void startIngestFromPath_ShouldRejectAPathThatIsNotAnExistingFolder() {
        final String missingFolder = tempDir.resolve("missing").toString();
        when(ioService.getExistingFolder(missingFolder))
                .thenReturn(Mono.error(new InvalidIngestionSourceException("The path is not an existing directory")));

        assertThrows(InvalidIngestionSourceException.class, () -> ingestionService.startIngestFromPath(missingFolder).block());
        assertThrows(InvalidIngestionSourceException.class, () -> ingestionService.startIngestFromPath(" ").block());
        assertThrows(InvalidIngestionSourceException.class, () -> ingestionService.startIngestFromPath(null).block());

        verify(ragService, never()).disableIndexing();
    }

    @Test
    void startCloneAndIngest_ShouldCloneAndIngestTheRepository() {
        ingestionService.startCloneAndIngest(SPRING_AI_URL).block();

        final Project project = chunkedProject();
        verify(ragService, timeout(TIMEOUT_MILLIS)).ingestChunksBatch(anyList());
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).start("spring-ai");

        assertEquals("spring-ai", project.getProjectName());
        assertEquals(SPRING_AI_URL, project.getProjectUrl());
        assertEquals(repositoryPath(SPRING_AI_URL), project.getProjectPath());
        assertEquals(wikiPath(SPRING_AI_URL), project.getProjectRepoWikiPath());
        assertEquals(List.of(repositoryPath(SPRING_AI_URL).resolve("README.md"), wikiPath(SPRING_AI_URL).resolve("README.md")),
                chunkedFiles());
    }

    @Test
    void startCloneAndIngest_ShouldIngestTheRepositoryWithoutWiki_WhenTheWikiCannotBeCloned() {
        when(gitService.cloneWiki(SPRING_AI_URL)).thenReturn(Mono.error(new IllegalStateException("wiki not found")));

        ingestionService.startCloneAndIngest(SPRING_AI_URL).block();

        final Project project = chunkedProject();
        assertEquals(repositoryPath(SPRING_AI_URL), project.getProjectPath());
        assertNull(project.getProjectRepoWikiPath());
        assertEquals(List.of(repositoryPath(SPRING_AI_URL).resolve("README.md")), chunkedFiles());
    }

    @Test
    void startCloneAndIngest_ShouldNotIngest_WhenTheRepositoryCannotBeCloned() {
        when(gitService.cloneRepository(SPRING_AI_URL)).thenReturn(Mono.error(new IllegalStateException("repository not found")));

        ingestionService.startCloneAndIngest(SPRING_AI_URL).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        verify(chunkingService, never()).chunkProject(any(), any());
        assertEquals(List.of(INDEXING_DISABLED, INDEXING_ENABLED), events);
    }

    @Test
    void startCloneAndIngest_ShouldRejectUrlsWithoutAValidProjectName() {
        for (final String repositoryUrl : List.of("", " ", "https://github.com/owner/..", "https://github.com/owner/.",
                "https://github.com/owner/", "https://github.com/owner/a b")) {
            assertThrows(InvalidIngestionSourceException.class,
                    () -> ingestionService.startCloneAndIngest(repositoryUrl).block(),
                    "Should reject the repository URL: '" + repositoryUrl + "'");
        }

        verify(gitService, never()).cloneRepository(anyString());
    }

    @Test
    void startDemo_ShouldIngestTheConfiguredRepositoriesOneAfterTheOther() {
        when(ingestionProperties.getDemoRepositories()).thenReturn(List.of(SPRING_SECURITY_URL, SPRING_AI_URL));

        ingestionService.startDemo().block();

        final InOrder order = inOrder(gitService);
        order.verify(gitService, timeout(TIMEOUT_MILLIS)).cloneRepository(SPRING_SECURITY_URL);
        order.verify(gitService, timeout(TIMEOUT_MILLIS)).cloneRepository(SPRING_AI_URL);
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        assertEquals(List.of(INDEXING_DISABLED, CHUNKS_INGESTED, OLD_CHUNKS_DELETED, CHUNKS_INGESTED, OLD_CHUNKS_DELETED, INDEXING_ENABLED), events);
    }

    @Test
    void start_ShouldRejectASecondIngestionWhileOneIsRunning_AndAcceptOneAfterItEnds() {
        final Sinks.Many<InformationChunk> runningIngestion = Sinks.many().unicast().onBackpressureBuffer();
        when(chunkingService.chunkProject(any(), any()))
                .thenReturn(runningIngestion.asFlux())
                .thenReturn(Flux.just(chunk));

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(chunkingService, timeout(TIMEOUT_MILLIS)).chunkProject(any(), any());

        assertThrows(IngestionAlreadyRunningException.class, () -> ingestionService.startDemo().block());
        assertThrows(IngestionAlreadyRunningException.class,
                () -> ingestionService.startIngestFromPath(tempDir.toString()).block());

        runningIngestion.tryEmitComplete();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(chunkingService, timeout(TIMEOUT_MILLIS).times(2)).chunkProject(any(), any());
    }

    @Test
    void start_ShouldEnableIndexingAndReleaseTheLock_WhenTheIngestionFails() {
        when(chunkingService.chunkProject(any(), any()))
                .thenReturn(Flux.error(new IllegalStateException("model not available")))
                .thenReturn(Flux.just(chunk));

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        assertEquals(List.of(INDEXING_DISABLED, INDEXING_ENABLED), events);

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS).times(2)).logSummary(any());

        assertEquals(List.of(INDEXING_DISABLED, INDEXING_ENABLED, INDEXING_DISABLED, CHUNKS_INGESTED, OLD_CHUNKS_DELETED, INDEXING_ENABLED), events);
    }

    @Test
    @SuppressWarnings("unchecked")
    void startIngestFromPath_ShouldStampTheChunksAndDeleteTheOnesOfPreviousIngestions() {
        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        final ArgumentCaptor<List<InformationChunk>> batch = ArgumentCaptor.forClass(List.class);
        final ArgumentCaptor<String> ingestionId = ArgumentCaptor.forClass(String.class);
        verify(ragService).ingestChunksBatch(batch.capture());
        verify(ragService).deleteOldIngestionChunks(eq(tempDir.getFileName().toString()), ingestionId.capture());

        assertEquals(chunk.getChunkId(), batch.getValue().get(0).getChunkId());
        assertEquals(ingestionId.getValue(), batch.getValue().get(0).getIngestionId());
    }

    @Test
    void startIngestFromPath_ShouldKeepTheChunksOfPreviousIngestions_WhenTheIngestionHadFailures() {
        when(ingestionProgress.hasFailures(anyString())).thenReturn(true);

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        assertEquals(List.of(INDEXING_DISABLED, CHUNKS_INGESTED, INDEXING_ENABLED), events);
    }

    @Test
    void startIngestFromPath_ShouldNotIngest_WhenTheCollectionCannotBePrepared() {
        when(ragService.prepareCollection()).thenReturn(Mono.error(new IllegalStateException("store not reachable")));

        ingestionService.startIngestFromPath(tempDir.toString()).block();
        verify(ingestionProgress, timeout(TIMEOUT_MILLIS)).logSummary(any());

        assertEquals(List.of(), events);
    }
}
