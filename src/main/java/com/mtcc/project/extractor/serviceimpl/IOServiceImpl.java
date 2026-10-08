package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.config.IOProperties;
import com.mtcc.project.extractor.exception.InvalidIngestionSourceException;
import com.mtcc.project.extractor.util.FileUtils;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.stream.Collectors;

import static com.mtcc.project.extractor.util.FileUtils.getLowerCaseExtension;
import static com.mtcc.project.extractor.util.FileUtils.getObjectFromJsonFilename;

@Slf4j
@Service
@RequiredArgsConstructor
public class IOServiceImpl implements IIOServiceImpl {

    private final IOProperties ioProperties;
    private final ChunkingProperties chunkingProperties;

    @Override
    public Mono<Path> createFolder(final String projectName) {
        final Path downloadFolder = Paths.get(ioProperties.getProjectsDownloadFolder())
                .toAbsolutePath()
                .normalize();
        final Path projectPath = downloadFolder.resolve(projectName).normalize();

        if (!projectPath.startsWith(downloadFolder) || projectPath.equals(downloadFolder)) {
            return Mono.error(new InvalidIngestionSourceException("Invalid project name: " + projectName));
        }

        return this.deleteFolder(projectPath)
                .flatMap(path -> Try.of(() -> Files.createDirectories(path))
                        .map(Mono::just)
                        .getOrElseGet(Mono::error))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Path> deleteFolder(final Path pathToDelete) {
        return Mono.just(pathToDelete)
                .filter(Files::exists)
                .map(path -> Try.withResources(() -> Files.walk(path))
                        .of(walk -> walk.sorted(Comparator.reverseOrder())
                                .map(file -> Try.of(() -> {
                                            file.toFile().setWritable(true);
                                            return Files.deleteIfExists(file);
                                        })
                                        .getOrElseThrow(Exceptions::propagate))
                                .toList())
                        .getOrElseThrow(Exceptions::propagate))
                .doOnSuccess(ok -> log.debug("Folder {} deleted", pathToDelete))
                .doOnError(error ->
                        log.error("Cannot delete file/folder: {}", pathToDelete))
                .then(Mono.just(pathToDelete));
    }

    @Override
    public Mono<Path> getExistingFolder(final String folderPath) {
        return Mono.fromCallable(() -> Paths.get(folderPath).toAbsolutePath().normalize())
                .filter(Files::isDirectory)
                .switchIfEmpty(Mono.error(() ->
                        new InvalidIngestionSourceException("The path is not an existing directory: " + folderPath)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> readText(final Path filePath) {
        return Mono.fromCallable(() -> decode(filePath, Files.readAllBytes(filePath)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public <T> Mono<T> readJsonResource(final String resourcePath, final Class<T> type) {
        return Mono.fromCallable(() -> getObjectFromJsonFilename(resourcePath, type))
                .doOnError(error -> log.error("Cannot read {}: {}", resourcePath, error.toString()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String decode(final Path filePath, final byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            log.debug("{} is not valid UTF-8, reading it as ISO-8859-1", filePath);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
    }

    @Override
    public Flux<Path> getAllFiles(final String directoryPath) {
        final Path rootPath = Paths.get(directoryPath).toAbsolutePath().normalize();

        final BiPredicate<Path, BasicFileAttributes> fileMatcher = (filePath, attrs) -> {
            if (!attrs.isRegularFile()) {
                return false;
            }

            if (Boolean.TRUE.equals(ioProperties.getIgnoreHiddenFilesAndFolders())) {
                for (Path element : rootPath.relativize(filePath)) {
                    if (element.toString().startsWith(".")) {
                        return false;
                    }
                }
            }

            return true;
        };

        return Mono.fromCallable(() -> {
                    final Set<String> supportedExtensions = chunkingProperties.getSupportedFileExtensions();
                    final Map<Boolean, List<Path>> filesBySupport = Try.withResources(() -> Files.find(rootPath, Integer.MAX_VALUE, fileMatcher))
                            .of(files -> files.collect(Collectors.partitioningBy(file ->
                                    supportedExtensions.contains(getLowerCaseExtension(file)))))
                            .getOrElseThrow(Exceptions::propagate);

                    final Map<String, Long> skippedByExtension = filesBySupport.get(false).stream()
                            .collect(Collectors.groupingBy(FileUtils::getLowerCaseExtension, TreeMap::new, Collectors.counting()));
                    log.info("Found {} files to ingest in {}, skipped by unsupported extension: {}",
                            filesBySupport.get(true).size(), rootPath, skippedByExtension);

                    return filesBySupport.get(true);
                })
                .doOnError(error -> log.error("Error retrieving files.", error))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(Flux::fromIterable);
    }
}
