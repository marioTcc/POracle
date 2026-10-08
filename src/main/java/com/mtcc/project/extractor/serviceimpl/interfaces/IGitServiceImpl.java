package com.mtcc.project.extractor.serviceimpl.interfaces;

import reactor.core.publisher.Mono;

import java.nio.file.Path;

public interface IGitServiceImpl {

    Mono<Path> cloneResource(final String url, final Path directory);
    Mono<Path> cloneRepository(final String projectUrl);
    Mono<Path> cloneWiki(final String projectUrl);
}
