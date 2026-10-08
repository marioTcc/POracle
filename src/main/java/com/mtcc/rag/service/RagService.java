package com.mtcc.rag.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.rag.exception.CollectionNotFoundException;
import com.mtcc.rag.serviceimpl.interfaces.IEmbeddingServiceImpl;
import com.mtcc.rag.serviceimpl.interfaces.IRagServiceImpl;
import com.mtcc.rag.serviceimpl.interfaces.IRerankServiceImpl;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RagService {

    private final IRagServiceImpl ragServiceImpl;
    private final IEmbeddingServiceImpl embeddingService;
    private final IRerankServiceImpl rerankService;
    private final IngestionProgress ingestionProgress;

    public Mono<List<RetrievedChunk>> searchChunks(final String query, final String projectName, final String question) {
        return embeddingService.generateQueryEmbedding(query)
                .flatMap(queryVector -> ragServiceImpl.searchSimilarChunks(query, queryVector, projectName))
                .flatMap(candidates -> rerankService.rerank(StringUtils.defaultIfBlank(question, query), candidates));
    }

    public Mono<List<List<Float>>> embedTexts(final List<String> texts) {
        return embeddingService.generateTextEmbeddings(texts);
    }

    public Mono<List<InformationChunk>> findChunksByType(final InformationType informationType, final int limit) {
        return ragServiceImpl.findChunksByType(informationType, limit);
    }

    public Mono<Map<String, Long>> countChunksByProject() {
        return ragServiceImpl.countChunksByProject();
    }

    public Mono<Void> deleteCollection(final String collectionName) {
        return ragServiceImpl.deleteCollection(collectionName)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(() -> new CollectionNotFoundException(collectionName)))
                .then();
    }

    public Mono<Void> disableIndexing() {
        return ragServiceImpl.disableIndexing()
                .onErrorResume(error -> Mono.empty());
    }

    public Mono<Void> enableIndexing() {
        return ragServiceImpl.enableIndexing()
                .onErrorResume(error -> Mono.empty());
    }

    public Mono<Void> prepareCollection() {
        return embeddingService.getDimensions()
                .flatMap(ragServiceImpl::createCollectionIfMissing);
    }

    public Mono<Void> deleteOldIngestionChunks(final String projectName, final String ingestionId) {
        return ragServiceImpl.deleteOldIngestionChunks(projectName, ingestionId)
                .onErrorResume(error -> Mono.empty());
    }

    public Flux<InformationChunk> ingestChunksBatch(final List<InformationChunk> chunks) {
        final String projectName = chunks.isEmpty() ? "unknown" : chunks.get(0).getProjectName();

        return embeddingService.generatePassageEmbeddings(chunks)
                .flatMap(batchList -> ragServiceImpl.upsertChunkBatch(batchList)
                        .publishOn(Schedulers.boundedElastic())
                        .thenReturn(batchList.stream()
                                .map(Tuple2::getT1)
                                .toList()))
                .doOnNext(batchList -> ingestionProgress.chunksUpserted(projectName, batchList.size()))
                .flatMapIterable(batchList -> batchList)
                .onErrorResume(error -> {
                    ingestionProgress.chunksFailed(projectName, chunks.size());
                    return Mono.empty();
                });
    }
}
