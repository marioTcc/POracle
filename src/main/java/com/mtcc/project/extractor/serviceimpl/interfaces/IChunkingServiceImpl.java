package com.mtcc.project.extractor.serviceimpl.interfaces;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.entity.Project;
import reactor.core.publisher.Flux;

import java.nio.file.Path;

public interface IChunkingServiceImpl {

    Flux<InformationChunk> chunkFile(final Project project, final Path filePath, final InformationType infoType);
}
