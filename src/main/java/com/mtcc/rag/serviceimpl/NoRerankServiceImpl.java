package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.rag.serviceimpl.interfaces.IRerankServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;

@Service
@ConditionalOnProperty(name = "RERANK.PROVIDER", havingValue = "none", matchIfMissing = true)
public class NoRerankServiceImpl implements IRerankServiceImpl {

    @Override
    public Mono<List<RetrievedChunk>> rerank(final String query, final List<RetrievedChunk> candidates) {
        return Mono.just(candidates);
    }
}
