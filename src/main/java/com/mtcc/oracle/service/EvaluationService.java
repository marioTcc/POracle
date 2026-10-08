package com.mtcc.oracle.service;

import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.oracle.config.EvaluationProperties;
import com.mtcc.oracle.entity.AgentAnswer;
import com.mtcc.oracle.entity.Dataset;
import com.mtcc.oracle.entity.DatasetRecord;
import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.entity.EvaluationScore;
import com.mtcc.oracle.entity.ItemEvaluation;
import com.mtcc.oracle.exception.EvaluationAlreadyRunningException;
import com.mtcc.oracle.exception.InvalidDatasetException;
import com.mtcc.oracle.observability.ExperimentTrace;
import com.mtcc.oracle.serviceimpl.interfaces.IObservabilityServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private static final String SCORE_COMMENT = "Measured by the evaluation run";
    private static final String NO_CATEGORY = "uncategorized";
    private static final int UPLOAD_CONCURRENCY = 3;

    private final IObservabilityServiceImpl observabilityService;
    private final IIOServiceImpl ioService;
    private final EvaluationProperties evaluationProperties;
    private final AgentService agentService;
    private final EvaluationMetricsService evaluationMetricsService;

    private final AtomicBoolean isRunning = new AtomicBoolean();

    public Mono<Void> uploadDefaultDataset() {
        return getDefaultDataset()
                .flatMap(this::uploadDataset);
    }

    public Mono<Void> uploadDataset(final Dataset dataset) {
        if (dataset == null || StringUtils.isBlank(dataset.getDatasetName())) {
            return Mono.error(new InvalidDatasetException("The dataset has no name"));
        }

        final List<DatasetRecord> records = Optional.ofNullable(dataset.getRecords()).orElse(List.of());

        return observabilityService.upsertDataset(dataset.getDatasetName(), dataset.getDatasetDescription())
                .thenMany(Flux.fromIterable(records))
                .flatMap(datasetRecord -> observabilityService.postDatasetItem(
                        dataset.getDatasetName(), datasetRecord.getQuestion(), datasetRecord.getAnswer(),
                        Map.of("category", StringUtils.defaultString(datasetRecord.getCategory()))), UPLOAD_CONCURRENCY)
                .then();
    }

    private Mono<Dataset> getDefaultDataset() {
        return ioService.readJsonResource(evaluationProperties.getDefaultDataset(), Dataset.class);
    }

    public Mono<Void> startRun(final String datasetName) {
        return Mono.fromRunnable(() -> {
            if (!isRunning.compareAndSet(false, true)) {
                throw new EvaluationAlreadyRunningException();
            }

            run(datasetName)
                    .doFinally(signal -> isRunning.set(false))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(ok -> { }, error -> log.error("Evaluation run failed", error));
        });
    }

    private Mono<Void> run(final String requestedDatasetName) {
        return Mono.justOrEmpty(requestedDatasetName)
                .filter(StringUtils::isNotBlank)
                .switchIfEmpty(Mono.defer(() -> getDefaultDataset().map(Dataset::getDatasetName)))
                .flatMap(datasetName -> observabilityService.getDatasetItems(datasetName)
                        .flatMap(items -> evaluate(items, datasetName + "-" + LocalDateTime.now())));
    }

    private Mono<Void> evaluate(final List<EvaluationItem> items, final String runName) {
        return evaluationMetricsService.measureSimilarityBaseline(items)
                .doOnNext(similarityBaseline -> log.info("Evaluation run {} started: {} items, similarity baseline {}",
                        runName, items.size(), similarityBaseline))
                .flatMapMany(similarityBaseline -> Flux.fromIterable(items)
                        .flatMap(item -> evaluate(item, runName, similarityBaseline),
                                Math.max(1, evaluationProperties.getConcurrency())))
                .collectList()
                .doOnNext(evaluations -> logSummary(runName, evaluations))
                .then();
    }

    private Mono<ItemEvaluation> evaluate(final EvaluationItem item, final String runName, final double similarityBaseline) {
        return observabilityService.startExperiment(runName, item)
                .flatMap(trace -> agentService.answerWithSources(item.getInput())
                        .doOnNext(answer -> trace.setOutput(answer.getText()))
                        .flatMap(answer -> evaluationMetricsService.measure(item, answer, similarityBaseline)
                                .concatMap(score -> postScore(trace, score))
                                .collectList()
                                .doOnNext(postedScores -> logItem(runName, item, answer, postedScores)))
                        .defaultIfEmpty(List.of())
                        .contextWrite(trace::addTo)
                        .doFinally(signal -> trace.end()))
                .onErrorResume(error -> {
                    log.warn("Cannot evaluate dataset item {}: {}", item.getId(), error.toString());
                    return Mono.just(List.of());
                })
                .map(postedScores -> ItemEvaluation.builder().category(item.getCategory()).scores(postedScores).build());
    }

    private void logItem(final String runName, final EvaluationItem item, final AgentAnswer answer,
                         final List<EvaluationScore> scores) {
        final String sources = answer.getSources().stream()
                .map(source -> source.getProjectName() + ":" + StringUtils.defaultIfBlank(source.getFilePath(),
                        String.valueOf(source.getInformationType())))
                .distinct()
                .collect(Collectors.joining(", "));

        log.info("Evaluation run {}, item {} [{}] \"{}\": {} | sources: {}", runName, item.getId(),
                StringUtils.defaultIfBlank(item.getCategory(), NO_CATEGORY), item.getInput(),
                scores.stream().map(this::format).collect(Collectors.joining(", ")), sources);
    }

    private String format(final EvaluationScore score) {
        return score.getName() + "=" + String.format(Locale.ROOT, "%.2f", score.getValue());
    }

    private void logSummary(final String runName, final List<ItemEvaluation> evaluations) {
        final List<ItemEvaluation> evaluated = evaluations.stream()
                .filter(evaluation -> !evaluation.getScores().isEmpty())
                .toList();

        log.info("Evaluation run {} completed: {} of {} items evaluated", runName, evaluated.size(), evaluations.size());
        log.info("Evaluation run {}, all categories ({} items): {}", runName, evaluated.size(), getAverageScores(evaluated));

        evaluated.stream()
                .collect(Collectors.groupingBy(evaluation -> StringUtils.defaultIfBlank(evaluation.getCategory(), NO_CATEGORY),
                        TreeMap::new, Collectors.toList()))
                .forEach((category, ofCategory) -> log.info("Evaluation run {}, category {} ({} items): {}",
                        runName, category, ofCategory.size(), getAverageScores(ofCategory)));
    }

    private String getAverageScores(final List<ItemEvaluation> evaluations) {
        return evaluations.stream()
                .flatMap(evaluation -> evaluation.getScores().stream())
                .collect(Collectors.groupingBy(EvaluationScore::getName, TreeMap::new,
                        Collectors.averagingDouble(EvaluationScore::getValue)))
                .entrySet().stream()
                .map(score -> score.getKey() + "=" + String.format(Locale.ROOT, "%.2f", score.getValue()))
                .collect(Collectors.joining(", "));
    }

    private Mono<EvaluationScore> postScore(final ExperimentTrace trace, final EvaluationScore score) {
        return observabilityService.postScore(trace, score.getName(), score.getValue(), SCORE_COMMENT)
                .thenReturn(score)
                .onErrorResume(error -> Mono.empty());
    }
}
