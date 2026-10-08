package com.mtcc.project.extractor.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.common.security.Redaction;
import com.mtcc.common.security.SecretRedactor;
import com.mtcc.oracle.service.AgentService;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.project.extractor.util.SummaryTexts;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;

import static com.mtcc.common.entity.InformationChunk.buildChunk;
import static com.mtcc.project.extractor.util.FileUtils.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private final ChunkingProperties chunkingProperties;
    private final IngestionProgress ingestionProgress;
    private final SecretRedactor secretRedactor;
    private final AgentService agentService;
    private final IIOServiceImpl ioService;

    private final IChunkingServiceImpl codeChunkingServiceImpl;
    private final IChunkingServiceImpl textChunkingServiceImpl;
    private final IChunkingServiceImpl wholeFileChunkingServiceImpl;

    public Flux<InformationChunk> chunkProject(final Project project, final Flux<Path> projectFiles) {
        return Flux.merge(
                        getProjectStructure(project, projectFiles),
                        getProjectSummaries(project, projectFiles),
                        projectFiles.flatMap(file -> this.analyzeFile(project, file), 1))
                .map(this::redactSecrets);
    }

    private InformationChunk redactSecrets(final InformationChunk chunk) {
        if (!Boolean.TRUE.equals(chunkingProperties.getSecretsRedactionEnabled())) {
            return chunk;
        }

        final Redaction redaction = secretRedactor.redact(
                chunk.getText(), chunk.getInformationType() == InformationType.CONFIG);

        if (redaction.getCount() == 0) {
            return chunk;
        }

        log.debug("Redacted {} secrets in {} of project {}", redaction.getCount(), chunk.getFilePath(), chunk.getProjectName());
        ingestionProgress.secretsRedacted(chunk.getProjectName(), redaction.getCount());
        return chunk.toBuilder().text(redaction.getText()).build();
    }

    public Mono<InformationChunk> getProjectStructure(final Project project, final Flux<Path> projectFiles) {
        return projectFiles
                .map(file -> getRelativePath(project, file))
                .collectList()
                .map(filePaths -> getProjectFilesTree(filePaths, chunkingProperties.getProjectTreeMaxDepth()))
                .doOnNext(tree -> log.info("Analyzing the file tree of project {}", project.getProjectName()))
                .flatMap(agentService::analyzeProjectStructure)
                .map(tree -> buildChunk(project.getProjectName(), "", SummaryTexts.LANGUAGE,
                        InformationType.PROJECT_STRUCTURE, tree, SummaryTexts.PROJECT_STRUCTURE_CONTEXT, SummaryTexts.STRATEGY));
    }

    public Flux<InformationChunk> getProjectSummaries(final Project project, final Flux<Path> projectFiles) {
        final Flux<Path> readmes = projectFiles
                .filter(file -> !isWikiFile(project, file))
                .filter(file -> file.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("readme."))
                .cache();

        final Mono<InformationChunk> projectSummary = readmes
                .filter(readme -> getDepth(project, readme) == 0)
                .next()
                .flatMap(readme -> summarizeProject(project, readme));

        final Flux<InformationChunk> moduleSummaries = readmes
                .filter(readme -> getDepth(project, readme) > 0
                        && getDepth(project, readme) <= chunkingProperties.getSummaryModuleMaxDepth())
                .sort(Comparator.comparing((Path readme) -> getRelativePath(project, readme)))
                .take(chunkingProperties.getSummaryMaxModules())
                .concatMap(readme -> summarizeModule(project, readme));

        return Flux.concat(projectSummary, moduleSummaries);
    }

    private Mono<InformationChunk> summarizeProject(final Project project, final Path readme) {
        final String projectName = project.getProjectName();

        return readDocumentation(readme)
                .flatMap(documentation -> agentService.summarizeProject(projectName, documentation))
                .map(summary -> buildChunk(projectName, getRelativePath(project, readme), SummaryTexts.LANGUAGE,
                        InformationType.PROJECT_SUMMARY, SummaryTexts.getProjectOverview(projectName, summary),
                        SummaryTexts.PROJECT_OVERVIEW_CONTEXT, SummaryTexts.STRATEGY))
                .onErrorResume(error -> Mono.empty());
    }

    private Mono<InformationChunk> summarizeModule(final Project project, final Path readme) {
        final String projectName = project.getProjectName();
        final String readmePath = getRelativePath(project, readme);
        final String modulePath = StringUtils.defaultString(StringUtils.substringBeforeLast(readmePath, "/"));

        return readDocumentation(readme)
                .flatMap(documentation -> agentService.summarizeModule(projectName, modulePath, documentation))
                .map(summary -> buildChunk(projectName, readmePath, SummaryTexts.LANGUAGE,
                        InformationType.MODULE_SUMMARY, SummaryTexts.getModuleOverview(projectName, modulePath, summary),
                        SummaryTexts.getModuleOverviewContext(modulePath), SummaryTexts.STRATEGY))
                .onErrorResume(error -> Mono.empty());
    }

    private Mono<String> readDocumentation(final Path readme) {
        return ioService.readText(readme)
                .map(documentation -> StringUtils.left(documentation, chunkingProperties.getSummaryMaxInputChars()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private int getDepth(final Project project, final Path file) {
        return StringUtils.countMatches(getRelativePath(project, file), '/');
    }

    private Flux<InformationChunk> analyzeFile(final Project project, final Path filePath) {
        final InformationType informationType = getInformationType(filePath);

        return Mono.just(informationType)
                .publishOn(Schedulers.boundedElastic())
                .flatMapMany(infoType -> infoType == InformationType.CODE ?
                        codeChunkingServiceImpl.chunkFile(project, filePath, infoType) :
                        textChunkingServiceImpl.chunkFile(project, filePath, infoType))
                .switchIfEmpty(wholeFileChunkingServiceImpl.chunkFile(project, filePath, informationType))
                .onErrorResume(error -> wholeFileChunkingServiceImpl.chunkFile(project, filePath, informationType))
                .doOnComplete(() -> ingestionProgress.fileProcessed(project.getProjectName()))
                .onErrorResume(error -> {
                    ingestionProgress.fileSkipped(project.getProjectName());
                    return Flux.empty();
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private InformationType getInformationType(final Path path) {
        final String extension = getLowerCaseExtension(path);

        if (chunkingProperties.getCodeFileExtensions().contains(extension)) {
            return InformationType.CODE;
        } else if (chunkingProperties.getDocsFileExtensions().contains(extension)) {
            return InformationType.DOC;
        } else return InformationType.CONFIG;
    }
}
