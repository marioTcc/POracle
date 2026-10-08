package com.mtcc.rag.serviceimpl.interfaces;

import com.mtcc.common.entity.InformationChunk;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.List;

public interface IEmbeddingServiceImpl {

    Mono<List<Float>> generateQueryEmbedding(final String query);
    Mono<List<Tuple2<InformationChunk, List<Float>>>> generatePassageEmbeddings(final List<InformationChunk> chunks);
    Mono<List<List<Float>>> generateTextEmbeddings(final List<String> texts);
    Mono<Integer> getDimensions();
}
