package com.mtcc.rag.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class OllamaClientProperties {

    @Value("${OLLAMA.BASE.URL}")
    private String ollamaBaseUrl;

    @Value("${OLLAMA.EMBEDDING.MODEL.NAME}")
    private String ollamaEmbeddingModelName;

    @Value("${OLLAMA.EMBEDDING.QUERY.PREFIX:}")
    private String ollamaEmbeddingQueryPrefix;

    @Value("${OLLAMA.EMBEDDING.PASSAGE.PREFIX:}")
    private String ollamaEmbeddingPassagePrefix;
}
