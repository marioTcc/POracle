package com.mtcc.rag.serviceimpl.interfaces;

import com.mtcc.common.entity.RetrievedChunk;
import reactor.core.publisher.Mono;

import java.util.List;

public interface IRerankServiceImpl {

    Mono<List<RetrievedChunk>> rerank(final String query, final List<RetrievedChunk> candidates);
}
