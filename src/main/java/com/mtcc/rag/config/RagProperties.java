package com.mtcc.rag.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class RagProperties {

    @Value("${EMBEDDING.MAX.THREADS:0}")
    private Integer embeddingMaxThreads;

    @Value("${RERANK.TOP.K:6}")
    private Integer rerankTopK;

    @Value("${RERANK.MODEL.PATH:}")
    private String rerankModelPath;

    @Value("${RERANK.TOKENIZER.PATH:}")
    private String rerankTokenizerPath;
}
