package com.mtcc.common.serviceimpl.interfaces;

import reactor.core.publisher.Mono;

public interface IRepositoryFileServiceImpl {

    Mono<String> readFile(final String repositoryUrl, final String revision, final String filePath);
}
