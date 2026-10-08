package com.mtcc.oracle.service;

import com.mtcc.oracle.entity.AgentAnswer;
import com.mtcc.oracle.entity.Dataset;
import com.mtcc.oracle.entity.DatasetRecord;
import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.entity.EvaluationScore;
import com.mtcc.oracle.exception.EvaluationAlreadyRunningException;
import com.mtcc.oracle.exception.InvalidDatasetException;
import com.mtcc.oracle.observability.ExperimentTrace;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.oracle.config.EvaluationProperties;
import com.mtcc.oracle.serviceimpl.interfaces.IObservabilityServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.Context;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationServiceTest {

    private static final int TIMEOUT_MILLIS = 5000;
    private static final double SIMILARITY_BASELINE = 0.6;
    private static final String DATASET_NAME = "golden";
    private static final String DEFAULT_DATASET = "datasets/golden.json";
    private static final String TRACE_ID = "0af7651916cd43dd8448eb211c80319c";
    private static final String TRACE_KEY = "trace";
    private static final String FIRST_QUESTION = "Which projects do you know?";
    private static final String SECOND_QUESTION = "What is spring-ai?";

    @Mock
    private IObservabilityServiceImpl observabilityService;
    @Mock
    private IIOServiceImpl ioService;
    @Mock
    private EvaluationProperties evaluationProperties;
    @Mock
    private AgentService agentService;
    @Mock
    private EvaluationMetricsService evaluationMetricsService;
    @Mock
    private ExperimentTrace experimentTrace;
    @InjectMocks
    private EvaluationService evaluationService;

    private final List<String> tracesSeenByTheAgent = new CopyOnWriteArrayList<>();
    private final List<String> postedScores = new CopyOnWriteArrayList<>();

    private final Dataset dataset = Dataset.builder()
            .datasetName(DATASET_NAME)
            .datasetDescription("Golden dataset")
            .records(List.of(
                    DatasetRecord.builder().category("scope").question(FIRST_QUESTION).answer("Two.").build(),
                    DatasetRecord.builder().question(SECOND_QUESTION).answer("A framework.").build()))
            .build();

    private final EvaluationItem firstItem = EvaluationItem.builder().id("item-1").input(FIRST_QUESTION).expectedOutput("Two.")
            .category("scope").build();
    private final EvaluationItem secondItem = EvaluationItem.builder().id("item-2").input(SECOND_QUESTION).build();

    private final AgentAnswer firstAnswer = AgentAnswer.builder().text("I know two projects.").build();
    private final AgentAnswer secondAnswer = AgentAnswer.builder().text("It is a framework.").build();

    @BeforeEach
    void setUp() {
        when(evaluationProperties.getDefaultDataset()).thenReturn(DEFAULT_DATASET);
        when(ioService.readJsonResource(DEFAULT_DATASET, Dataset.class)).thenReturn(Mono.just(dataset));
        when(observabilityService.upsertDataset(anyString(), any())).thenReturn(Mono.empty());
        when(observabilityService.postDatasetItem(anyString(), any(), any(), anyMap())).thenReturn(Mono.empty());
        when(observabilityService.getDatasetItems(DATASET_NAME)).thenReturn(Mono.just(List.of(firstItem, secondItem)));
        when(observabilityService.startExperiment(anyString(), any())).thenReturn(Mono.just(experimentTrace));
        when(observabilityService.postScore(any(), anyString(), anyDouble(), anyString())).thenAnswer(call ->
                Mono.fromRunnable(() -> postedScores.add(call.getArgument(1) + "=" + call.getArgument(2))));
        when(experimentTrace.getTraceId()).thenReturn(TRACE_ID);
        when(experimentTrace.addTo(any())).thenAnswer(call -> call.<Context>getArgument(0).put(TRACE_KEY, TRACE_ID));

        when(agentService.answerWithSources(FIRST_QUESTION)).thenReturn(answer(firstAnswer));
        when(agentService.answerWithSources(SECOND_QUESTION)).thenReturn(answer(secondAnswer));
        when(evaluationMetricsService.measureSimilarityBaseline(anyList())).thenReturn(Mono.just(SIMILARITY_BASELINE));
        when(evaluationMetricsService.measure(firstItem, firstAnswer, SIMILARITY_BASELINE))
                .thenReturn(Flux.just(score("answer-recall", 0.5), score("groundedness", 1.0)));
        when(evaluationMetricsService.measure(secondItem, secondAnswer, SIMILARITY_BASELINE))
                .thenReturn(Flux.just(score("answer-recall", 0.8)));
    }

    private Mono<AgentAnswer> answer(final AgentAnswer answer) {
        return Mono.deferContextual(subscriberContext -> {
            tracesSeenByTheAgent.add(subscriberContext.getOrDefault(TRACE_KEY, "none"));
            return Mono.just(answer);
        });
    }

    private EvaluationScore score(final String name, final double value) {
        return EvaluationScore.builder().name(name).value(value).build();
    }

    private void runAndWait(final String datasetName) {
        evaluationService.startRun(datasetName).block();
        verify(experimentTrace, timeout(TIMEOUT_MILLIS).times(2)).end();
    }

    @Test
    void uploadDefaultDataset_ShouldCreateTheDatasetAndUploadEachRecord() {
        evaluationService.uploadDefaultDataset().block();

        verify(observabilityService).upsertDataset(DATASET_NAME, "Golden dataset");
        verify(observabilityService).postDatasetItem(DATASET_NAME, FIRST_QUESTION, "Two.", Map.of("category", "scope"));
        verify(observabilityService).postDatasetItem(DATASET_NAME, SECOND_QUESTION, "A framework.", Map.of("category", ""));
    }

    @Test
    void uploadDataset_ShouldFail_WhenTheDatasetCannotBeStored() {
        when(observabilityService.upsertDataset(anyString(), any())).thenReturn(Mono.error(new IllegalStateException("not reachable")));

        assertThrows(IllegalStateException.class, () -> evaluationService.uploadDataset(dataset).block());
    }

    @Test
    void uploadDataset_ShouldRejectADatasetWithoutName() {
        final Dataset unnamed = Dataset.builder().records(List.of()).build();

        assertThrows(InvalidDatasetException.class, () -> evaluationService.uploadDataset(unnamed).block());
        assertThrows(InvalidDatasetException.class, () -> evaluationService.uploadDataset(null).block());
        verify(observabilityService, never()).upsertDataset(any(), any());
    }

    @Test
    void startRun_ShouldAnswerEachItemInsideItsTraceAndPostItsScores() {
        runAndWait(DATASET_NAME);

        verify(observabilityService).startExperiment(startsWith(DATASET_NAME + "-"), eq(firstItem));
        verify(observabilityService).startExperiment(startsWith(DATASET_NAME + "-"), eq(secondItem));
        verify(evaluationMetricsService).measureSimilarityBaseline(List.of(firstItem, secondItem));
        verify(experimentTrace).setOutput("I know two projects.");
        verify(experimentTrace).setOutput("It is a framework.");
        assertEquals(List.of(TRACE_ID, TRACE_ID), tracesSeenByTheAgent);
        assertEquals(List.of("answer-recall=0.5", "groundedness=1.0", "answer-recall=0.8"), postedScores);
    }

    @Test
    void startRun_ShouldEvaluateSeveralItemsAtOnce_WhenTheConcurrencyAllowsIt() {
        final CompletableFuture<AgentAnswer> firstAnswerOnceTheSecondStarted = new CompletableFuture<>();
        when(evaluationProperties.getConcurrency()).thenReturn(2);
        when(agentService.answerWithSources(FIRST_QUESTION)).thenReturn(Mono.fromFuture(firstAnswerOnceTheSecondStarted));
        when(agentService.answerWithSources(SECOND_QUESTION)).thenReturn(Mono.fromSupplier(() -> {
            firstAnswerOnceTheSecondStarted.complete(firstAnswer);
            return secondAnswer;
        }));

        runAndWait(DATASET_NAME);

        assertEquals(3, postedScores.size(), postedScores.toString());
    }

    @Test
    void startRun_ShouldLogTheAverageScoresOfTheRunAndOfEachCategory(final CapturedOutput output) {
        runAndWait(DATASET_NAME);

        await(() -> output.getOut().contains("category uncategorized"));
        assertTrue(output.getOut().contains("completed: 2 of 2 items evaluated"), output.getOut());
        assertTrue(output.getOut().contains("all categories (2 items): answer-recall=0.65, groundedness=1.00"), output.getOut());
        assertTrue(output.getOut().contains("category scope (1 items): answer-recall=0.50, groundedness=1.00"), output.getOut());
        assertTrue(output.getOut().contains("category uncategorized (1 items): answer-recall=0.80"), output.getOut());
    }

    private void await(final BooleanSupplier condition) {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            Thread.onSpinWait();
        }
    }

    @Test
    void startRun_ShouldUseTheDefaultDataset_WhenNoneIsGiven() {
        runAndWait(null);

        verify(observabilityService).getDatasetItems(DATASET_NAME);
    }

    @Test
    void startRun_ShouldKeepGoing_WhenAnItemCannotBeAnswered() {
        when(agentService.answerWithSources(FIRST_QUESTION)).thenReturn(Mono.error(new IllegalStateException("model not available")));

        runAndWait(DATASET_NAME);

        assertEquals(List.of("answer-recall=0.8"), postedScores);
    }

    @Test
    void startRun_ShouldKeepPostingScores_WhenOneCannotBeSent() {
        when(observabilityService.postScore(any(), eq("answer-recall"), anyDouble(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("not reachable")));

        runAndWait(DATASET_NAME);

        assertEquals(List.of("groundedness=1.0"), postedScores);
    }

    @Test
    void startRun_ShouldRejectASecondRunWhileOneIsRunning_AndAcceptOneAfterItEnds() {
        final Sinks.One<List<EvaluationItem>> runningRun = Sinks.one();
        when(observabilityService.getDatasetItems(DATASET_NAME))
                .thenReturn(runningRun.asMono())
                .thenReturn(Mono.just(List.of(firstItem)));

        evaluationService.startRun(DATASET_NAME).block();
        verify(observabilityService, timeout(TIMEOUT_MILLIS)).getDatasetItems(DATASET_NAME);

        assertThrows(EvaluationAlreadyRunningException.class, () -> evaluationService.startRun(DATASET_NAME).block());

        runningRun.tryEmitValue(List.of(firstItem));
        verify(experimentTrace, timeout(TIMEOUT_MILLIS)).end();

        awaitRelease();
        verify(observabilityService, timeout(TIMEOUT_MILLIS).times(2)).getDatasetItems(DATASET_NAME);
    }

    private void awaitRelease() {
        final long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            try {
                evaluationService.startRun(DATASET_NAME).block();
                return;
            } catch (EvaluationAlreadyRunningException stillRunning) {
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("The evaluation lock was not released");
    }
}
