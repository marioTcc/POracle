package com.mtcc.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.e5smallv2q.E5SmallV2QuantizedEmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;

@Configuration
@RequiredArgsConstructor
public class EmbeddingModelConfig {

    private static final String PROVIDER = "EMBEDDING.PROVIDER";

    private final OllamaClientProperties ollamaClientProperties;
    private final RagProperties ragProperties;

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "in-process")
    public EmbeddingModel inProcessEmbeddingModel() {
        return new E5SmallV2QuantizedEmbeddingModel();
    }

    @Bean
    @ConditionalOnProperty(name = PROVIDER, havingValue = "ollama", matchIfMissing = true)
    public EmbeddingModel ollamaEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl(ollamaClientProperties.getOllamaBaseUrl())
                .modelName(ollamaClientProperties.getOllamaEmbeddingModelName())
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    @Bean(destroyMethod = "dispose")
    public Scheduler embeddingScheduler() {
        final int threads = ragProperties.getEmbeddingMaxThreads() > 0 ?
                ragProperties.getEmbeddingMaxThreads() : Runtime.getRuntime().availableProcessors();

        return Schedulers.newBoundedElastic(threads, Integer.MAX_VALUE, "embedding");
    }
}
