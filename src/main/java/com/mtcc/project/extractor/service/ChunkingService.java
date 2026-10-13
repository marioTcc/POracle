package com.mtcc.project.extractor.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.common.security.Redaction;
import com.mtcc.common.security.SecretRedactor;
import com.mtcc.oracle.service.AgentService;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.entity.ProjectFolder;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.project.extractor.util.ProjectTreeUtils;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mtcc.common.entity.InformationChunk.buildChunk;
import static com.mtcc.project.extractor.util.FileUtils.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChunkingService {

    private static final int MIN_FOLDER_ENTRY_CHARS = 200;
    private static final String FOLDER_ENTRY_SEPARATOR = "\n\n";
    private static final String FOLDER_MARK = "/";

    private final ChunkingProperties chunkingProperties;
    private final IngestionProgress ingestionProgress;
    private final SecretRedactor secretRedactor;
    private final AgentService agentService;
    private final IIOServiceImpl ioService;

    private final IChunkingServiceImpl codeChunkingServiceImpl;
    private final IChunkingServiceImpl textChunkingServiceImpl;
    private final IChunkingServiceImpl wholeFileChunkingServiceImpl;

    public Flux<InformationChunk> chunkProject(final Project project, final Flux<Path> projectFiles) {
        final Flux<InformationChunk> fileOutlines = projectFiles
                .flatMap(file -> this.analyzeFile(project, file), 1)
                .map(this::redactSecrets)
                .cache();

        return Flux.merge(
                        getProjectSummaries(project, projectFiles).map(this::redactSecrets),
                        fileOutlines,
                        getFolderSummaries(project, fileOutlines).map(this::redactSecrets))
                .map(chunk -> withRepository(project, chunk));
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

    private InformationChunk withRepository(final Project project, final InformationChunk chunk) {
        return chunk.toBuilder()
                .repositoryUrl(StringUtils.startsWith(chunk.getFilePath(), WIKI_PREFIX) ? null : project.getProjectUrl())
                .revision(project.getRevision())
                .build();
    }

    public Flux<InformationChunk> getFolderSummaries(final Project project, final Flux<InformationChunk> fileOutlines) {
        return Flux.defer(() -> {
            final Map<String, String> summaryByFolder = new ConcurrentHashMap<>();

            return fileOutlines.collectList()
                    .map(ProjectTreeUtils::getFoldersDeepestFirst)
                    .doOnNext(folders -> log.info("Summarizing the {} folders of project {}", folders.size(), project.getProjectName()))
                    .flatMapIterable(folders -> folders)
                    .concatMap(folder -> summarizeFolder(project, folder, summaryByFolder));
        });
    }

    private Mono<InformationChunk> summarizeFolder(final Project project, final ProjectFolder folder,
                                                   final Map<String, String> summaryByFolder) {
        final String projectName = project.getProjectName();
        final String folderPath = folder.getPath();

        return agentService.summarizeFolder(projectName, SummaryTexts.getFolderName(folderPath), getFolderContents(folder, summaryByFolder))
                .onErrorResume(error -> Mono.empty())
                .defaultIfEmpty("")
                .doOnNext(summary -> summaryByFolder.put(folderPath, summary))
                .map(summary -> buildChunk(projectName, folderPath, SummaryTexts.LANGUAGE, InformationType.FOLDER_SUMMARY,
                        SummaryTexts.getFolderOverview(projectName, folderPath, summary, getContentPaths(folder)),
                        SummaryTexts.getFolderOverviewContext(folderPath), SummaryTexts.STRATEGY));
    }

    private String getFolderContents(final ProjectFolder folder, final Map<String, String> summaryByFolder) {
        final int maxChars = chunkingProperties.getSummaryMaxInputChars();
        final int entries = folder.getSubfolders().size() + folder.getFiles().size();
        final int entryChars = Math.max(MIN_FOLDER_ENTRY_CHARS, maxChars / Math.max(entries, 1));

        final Stream<String> subfolders = folder.getSubfolders().stream()
                .map(subfolder -> "Folder " + subfolder + FOLDER_MARK + "\n"
                        + StringUtils.abbreviate(summaryByFolder.getOrDefault(subfolder, ""), entryChars));
        final Stream<String> files = folder.getFiles().stream()
                .map(file -> "File " + file.getFilename() + "\n" + StringUtils.abbreviate(file.getText(), entryChars));

        return StringUtils.left(Stream.concat(subfolders, files).collect(Collectors.joining(FOLDER_ENTRY_SEPARATOR)), maxChars);
    }

    private List<String> getContentPaths(final ProjectFolder folder) {
        return Stream.concat(
                        folder.getSubfolders().stream().map(subfolder -> subfolder + FOLDER_MARK),
                        folder.getFiles().stream().map(InformationChunk::getFilePath))
                .toList();
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
                .map(outline -> outline.toBuilder().localPath(filePath.toAbsolutePath().normalize().toString()).build())
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
