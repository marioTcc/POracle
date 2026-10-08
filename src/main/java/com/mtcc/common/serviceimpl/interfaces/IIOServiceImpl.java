package com.mtcc.common.serviceimpl.interfaces;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.file.Path;

public interface IIOServiceImpl {

    Mono<Path> createFolder(final String projectName);
    Mono<Path> getExistingFolder(final String folderPath);
    Flux<Path> getAllFiles(final String directoryPath);
    Mono<String> readText(final Path filePath);
    <T> Mono<T> readJsonResource(final String resourcePath, final Class<T> type);
}
