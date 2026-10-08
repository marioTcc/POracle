package com.mtcc.project.extractor.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.project.extractor.config.IngestionProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.exception.IngestionAlreadyRunningException;
import com.mtcc.project.extractor.exception.InvalidIngestionSourceException;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.project.extractor.serviceimpl.interfaces.IGitServiceImpl;
import com.mtcc.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.mtcc.project.extractor.util.ProjectNamingUtils.isValidProjectName;
import static com.mtcc.project.extractor.util.ProjectNamingUtils.urltoProjectName;

@Slf4j
@Service
@RequiredArgsConstructor
public class IngestionService {

    private final IGitServiceImpl gitService;
    private final ChunkingService chunkingService;
    private final RagService ragService;
    private final IngestionProgress ingestionProgress;
    private final IngestionProperties ingestionProperties;
    private final IIOServiceImpl ioService;

    private final AtomicBoolean isRunning = new AtomicBoolean();

    public Mono<Void> startDemo() {
        return start(ingestDemo());
    }

    public Mono<Void> startCloneAndIngest(final String repositoryUrl) {
        return Mono.fromCallable(() -> validateRepositoryUrl(repositoryUrl))
                .flatMap(url -> start(ingest(List.of(cloneProject(url)))));
    }

    public Mono<Void> startIngestFromPath(final String path) {
        return getLocalProject(path)
                .flatMap(project -> start(ingest(List.of(Mono.just(project)))));
    }

    private Mono<Void> start(final Mono<Void> ingestion) {
        return Mono.fromRunnable(() -> {
            acquire();
            ingestion.doFinally(signal -> isRunning.set(false))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(ok -> { }, error -> log.error("Ingestion failed", error));
        });
    }

    private void acquire() {
        if (!isRunning.compareAndSet(false, true)) {
            throw new IngestionAlreadyRunningException();
        }
    }

    private Mono<Void> ingestDemo() {
        return ingest(ingestionProperties.getDemoRepositories().stream()
                .map(this::cloneProject)
                .toList());
    }

    private Mono<Void> ingest(final List<Mono<Project>> projects) {
        return Mono.defer(() -> {
            final long startedAt = System.nanoTime();

            return ragService.prepareCollection()
                    .then(Mono.defer(() -> ingestWithoutIndexing(projects)))
                    .doFinally(signal -> ingestionProgress.logSummary(Duration.ofNanos(System.nanoTime() - startedAt)));
        });
    }

    private Mono<Void> ingestWithoutIndexing(final List<Mono<Project>> projects) {
        return ragService.disableIndexing()
                .thenMany(Flux.fromIterable(projects))
                .concatMap(project -> project.flatMapMany(this::ingestProject))
                .then()
                .onErrorResume(error -> ragService.enableIndexing().then(Mono.error(error)))
                .then(ragService.enableIndexing());
    }

    private Flux<InformationChunk> ingestProject(final Project project) {
        final String ingestionId = UUID.randomUUID().toString();

        return chunkingService.chunkProject(project, getProjectFiles(project))
                .map(chunk -> chunk.toBuilder().ingestionId(ingestionId).build())
                .bufferTimeout(ingestionProperties.getBatchSize(),
                        Duration.ofSeconds(ingestionProperties.getBatchTimeoutSeconds()))
                .onBackpressureBuffer(ingestionProperties.getMaxBufferedBatches(), queuedBuffer ->
                        log.warn("Ingestion buffer is full for project {}", project.getProjectName()))
                .concatMap(ragService::ingestChunksBatch)
                .concatWith(deleteChunksOfPreviousIngestions(project.getProjectName(), ingestionId))
                .doOnComplete(() -> ingestionProgress.logProjectSummary(project.getProjectName()));
    }

    private Flux<Path> getProjectFiles(final Project project) {
        final Flux<Path> wikiFiles = project.getProjectRepoWikiPath() == null ?
                Flux.empty() :
                ioService.getAllFiles(project.getProjectRepoWikiPath().toString());

        return ioService.getAllFiles(project.getProjectPath().toString())
                .mergeWith(wikiFiles)
                .cache()
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Flux<InformationChunk> deleteChunksOfPreviousIngestions(final String projectName, final String ingestionId) {
        return Mono.fromCallable(() -> ingestionProgress.hasFailures(projectName))
                .flatMap(hasFailures -> {
                    if (Boolean.TRUE.equals(hasFailures)) {
                        log.warn("Chunks of previous ingestion of project {} are kept: this ingestion had failures", projectName);
                        return Mono.empty();
                    }
                    return ragService.deleteOldIngestionChunks(projectName, ingestionId);
                })
                .thenMany(Flux.empty());
    }

    private Mono<Project> cloneProject(final String repositoryUrl) {
        return Mono.defer(() -> {
            ingestionProgress.start(urltoProjectName.apply(repositoryUrl));
            return gitService.cloneRepository(repositoryUrl)
                    .map(projectPath -> Project.builder()
                            .projectUrl(repositoryUrl)
                            .projectName(urltoProjectName.apply(repositoryUrl))
                            .projectPath(projectPath)
                            .build())
                    .flatMap(project -> cloneWiki(project, repositoryUrl));
        });
    }

    private Mono<Project> cloneWiki(final Project project, final String repositoryUrl) {
        return gitService.cloneWiki(repositoryUrl)
                .map(wikiPath -> project.toBuilder().projectRepoWikiPath(wikiPath).build())
                .onErrorReturn(project)
                .defaultIfEmpty(project);
    }

    private Mono<Project> getLocalProject(final String path) {
        return Mono.justOrEmpty(path)
                .filter(StringUtils::isNotBlank)
                .switchIfEmpty(Mono.error(() -> new InvalidIngestionSourceException("The path is empty")))
                .flatMap(ioService::getExistingFolder)
                .map(projectPath -> Project.builder()
                        .projectName(projectPath.getFileName().toString())
                        .projectPath(projectPath)
                        .build())
                .doOnNext(project -> ingestionProgress.start(project.getProjectName()));
    }

    private String validateRepositoryUrl(final String repositoryUrl) {
        if (StringUtils.isBlank(repositoryUrl)) {
            throw new InvalidIngestionSourceException("The repository URL is empty");
        }

        if (!isValidProjectName(urltoProjectName.apply(repositoryUrl.trim()))) {
            throw new InvalidIngestionSourceException("The repository URL does not end with a valid project name: " + repositoryUrl);
        }

        return repositoryUrl.trim();
    }
}
