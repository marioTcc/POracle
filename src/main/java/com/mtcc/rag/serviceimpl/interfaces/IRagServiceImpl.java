package com.mtcc.rag.serviceimpl.interfaces;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Map;

public interface IRagServiceImpl {

    Mono<Void> upsertChunkBatch(final List<Tuple2<InformationChunk, List<Float>>> chunksAndEmbeddings);
    Mono<InformationChunk> getChunk(final String chunkId);
    Mono<Void> deleteOldIngestionChunks(final String projectName, final String ingestionId);
    Mono<Void> createCollectionIfMissing(int vectorSize);
    Mono<List<RetrievedChunk>> searchSimilarChunks(final String query, final List<Float> queryVector, final String projectName);
    Mono<List<InformationChunk>> findChunksByType(final InformationType informationType, int limit);
    Mono<Map<String, Long>> countChunksByProject();
    Mono<Void> disableIndexing();
    Mono<Void> enableIndexing();
    Mono<Boolean> deleteCollection(final String collectionName);
}
