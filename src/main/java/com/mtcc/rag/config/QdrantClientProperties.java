package com.mtcc.rag.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class QdrantClientProperties {

    @Value("${QDRANT.HOST:localhost}")
    private String qdrantHost;

    @Value("${QDRANT.GRPC.PORT:6334}")
    private Integer qdrantGrpcPort;

    @Value("${QDRANT.INDEXING.THRESHOLD:20000}")
    private Integer qdrantIndexingThreshold;

    @Value("${QDRANT.SEARCH.TOP.K:8}")
    private Integer qdrantSearchTopK;

    @Value("${QDRANT.SEARCH.MIN.SCORE:0}")
    private Double qdrantSearchMinScore;

    @Value("${QDRANT.SEARCH.HYBRID.ENABLED:true}")
    private Boolean qdrantSearchHybridEnabled;

    @Value("${QDRANT.SEARCH.PREFETCH.LIMIT:20}")
    private Integer qdrantSearchPrefetchLimit;
}
