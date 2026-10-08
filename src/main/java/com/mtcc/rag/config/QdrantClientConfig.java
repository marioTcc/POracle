package com.mtcc.rag.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class QdrantClientConfig {

    private final QdrantClientProperties qdrantClientProperties;

    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient() {
        return new QdrantClient(QdrantGrpcClient
                .newBuilder(qdrantClientProperties.getQdrantHost(), qdrantClientProperties.getQdrantGrpcPort(), false)
                .build());
    }
}
