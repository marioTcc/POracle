package com.mtcc.rag.config;

import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class RerankModelConfig {

    private final RagProperties ragProperties;

    @Bean
    @ConditionalOnProperty(name = "RERANK.PROVIDER", havingValue = "in-process")
    public ScoringModel rerankModel() {
        return new OnnxScoringModel(ragProperties.getRerankModelPath(), ragProperties.getRerankTokenizerPath());
    }
}
