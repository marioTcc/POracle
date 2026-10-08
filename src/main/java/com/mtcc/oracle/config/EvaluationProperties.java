package com.mtcc.oracle.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class EvaluationProperties {

    @Value("${EVALUATION.DEFAULT.DATASET:datasets/spring-ai-security-golden-dataset_v2.json}")
    private String defaultDataset;

    @Value("${EVALUATION.MAX.SOURCES.CHARS:12000}")
    private Integer maxSourcesChars;

    @Value("${EVALUATION.CONCURRENCY:1}")
    private Integer concurrency;
}
