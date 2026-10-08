package com.mtcc.oracle.serviceimpl.interfaces;

import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.observability.ExperimentTrace;
import com.mtcc.oracle.observability.GenerationTrace;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface IObservabilityServiceImpl {
    Mono<Void> upsertDataset(final String datasetName, final String datasetDescription);
    Mono<Void> postDatasetItem(
            final String datasetName, final String question, final String answer, final Map<String, String> metadata);
    Mono<List<EvaluationItem>> getDatasetItems(final String datasetName);
    Mono<Void> postScore(final ExperimentTrace trace, final String scoreName, final double value, final String comment);
    GenerationTrace startGeneration(final String modelName, final String input);
    Mono<ExperimentTrace> startExperiment(final String runName, final EvaluationItem item);
}
