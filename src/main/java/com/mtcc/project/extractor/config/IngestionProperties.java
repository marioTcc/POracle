package com.mtcc.project.extractor.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Getter
@Component
public class IngestionProperties {

    @Value("${INGESTION.DEMO.REPOSITORIES}")
    private List<String> demoRepositories;

    @Value("${INGESTION.BATCH.SIZE}")
    private Integer batchSize;

    @Value("${INGESTION.BATCH.TIMEOUT.SECONDS}")
    private Integer batchTimeoutSeconds;

    @Value("${INGESTION.MAX.BUFFERED.BATCHES}")
    private Integer maxBufferedBatches;
}
