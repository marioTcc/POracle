package com.mtcc.oracle.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.oracle.config.EvaluationProperties;
import com.mtcc.oracle.entity.AgentAnswer;
import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.entity.EvaluationScore;
import com.mtcc.oracle.config.AgentProperties;
import com.mtcc.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationMetricsServiceTest {

    private static final double PRECISION = 1e-6;
    private static final String QUESTION = "How do I secure the OpenAI client?";
    private static final String EXPECTED_ANSWER = "Declare a `SecurityFilterChain` bean with @EnableWebSecurity,"
            + " set spring.ai.openai.api-key and add spring-boot-starter-security (e.g. in the pom).";
    private static final String ANSWER = "You need a securityfilterchain and the property spring.ai.openai.api-key.";

    @Mock
    private AgentService agentService;
    @Mock
    private RagService ragService;
    @Mock
    private AgentProperties agentProperties;
    @Mock
    private EvaluationProperties evaluationProperties;
    @InjectMocks
    private EvaluationMetricsService evaluationMetricsService;

    private final InformationChunk source = InformationChunk.buildChunk("spring-ai", "models/ChatClient.java", "JAVA",
            InformationType.CODE, "public interface ChatClient { String call(String prompt); }", "org.example.ChatClient", "code");

    private final EvaluationItem item = EvaluationItem.builder().id("item-1").input(QUESTION).expectedOutput(EXPECTED_ANSWER).build();
    private final AgentAnswer answer = AgentAnswer.builder().text(ANSWER).sources(List.of(source, source)).build();

    @BeforeEach
    void setUp() {
        when(agentProperties.getSearchMaxChunkChars()).thenReturn(1500);
        when(evaluationProperties.getMaxSourcesChars()).thenReturn(12000);
        when(agentService.judgeAnswerRecall(anyString(), anyString(), anyString())).thenReturn(Mono.just(0.5));
        when(agentService.judgeGroundedness(anyString(), anyString(), anyString())).thenReturn(Mono.just(1.0));
        when(agentService.judgeContextPrecision(anyString(), anyString())).thenReturn(Mono.just(0.4));
        when(agentService.judgeContextRecall(anyString(), anyString(), anyString())).thenReturn(Mono.just(0.7));
        when(ragService.embedTexts(List.of(EXPECTED_ANSWER, ANSWER))).thenReturn(Mono.just(List.of(List.of(1f, 0f), List.of(4f, 3f))));
    }

    private Map<String, Double> measure(final EvaluationItem measuredItem, final AgentAnswer measuredAnswer, final double baseline) {
        return evaluationMetricsService.measure(measuredItem, measuredAnswer, baseline).collectList().block().stream()
                .collect(Collectors.toMap(EvaluationScore::getName, EvaluationScore::getValue));
    }

    @Test
    void measure_ShouldReturnEveryScoreOfAnAnswerWithSources() {
        final Map<String, Double> scores = measure(item, answer, 0.6);

        assertEquals(8, scores.size(), scores.toString());
        assertEquals(0.7, scores.get("context-recall"), PRECISION);
        assertEquals(0.5, scores.get("answer-recall"), PRECISION);
        assertEquals(0.5, scores.get("semantic-similarity"), PRECISION);
        assertEquals(0.5, scores.get("answer-correctness"), PRECISION);
        assertEquals(0.5, scores.get("key-term-recall"), PRECISION);
        assertEquals(1.0, scores.get("groundedness"), PRECISION);
        assertEquals(0.4, scores.get("context-precision"), PRECISION);
        assertEquals(0.0, scores.get("refused"), PRECISION);
        verify(agentService, times(1)).judgeAnswerRecall(QUESTION, EXPECTED_ANSWER, ANSWER);
    }

    @Test
    void measure_ShouldGiveTheJudgeTheNumberedSources() {
        final String sources = "[1]\n" + source.toEmbeddingText() + "\n\n---\n\n[2]\n" + source.toEmbeddingText();

        measure(item, answer, 0.0);

        verify(agentService).judgeGroundedness(QUESTION, sources, ANSWER);
        verify(agentService).judgeContextPrecision(QUESTION, sources);
        verify(agentService).judgeContextRecall(QUESTION, EXPECTED_ANSWER, sources);
    }

    @Test
    void measure_ShouldKeepTheHeadAndTheTailOfEachSource_WhenTheSourcesAreLongerThanTheirLimit() {
        final InformationChunk longSource = source.toBuilder().text("a".repeat(500) + "END").build();
        final String text = longSource.toEmbeddingText();
        when(evaluationProperties.getMaxSourcesChars()).thenReturn(400);

        measure(item, AgentAnswer.builder().text(ANSWER).sources(List.of(longSource, longSource)).build(), 0.0);

        final String cutSource = text.substring(0, 130) + "\n...\n" + text.substring(text.length() - 65);
        assertEquals(200, cutSource.length());
        assertTrue(cutSource.endsWith("END"));
        verify(agentService).judgeContextPrecision(QUESTION, "[1]\n" + cutSource + "\n\n---\n\n[2]\n" + cutSource);
    }

    @Test
    void measure_ShouldRescaleTheSimilarityFromTheBaseline() {
        assertEquals(0.8, measure(item, answer, 0.0).get("semantic-similarity"), PRECISION);
        assertEquals(0.0, measure(item, answer, 0.95).get("semantic-similarity"), PRECISION);
        assertEquals(0.0, measure(item, answer, 1.0).get("semantic-similarity"), PRECISION);
    }

    @Test
    void measure_ShouldSkipTheScoresThatNeedSources_WhenTheAnswerHasNone() {
        final Map<String, Double> scores = measure(item, AgentAnswer.builder().text(ANSWER).build(), 0.6);

        assertEquals(5, scores.size(), scores.toString());
        verify(agentService, never()).judgeGroundedness(any(), any(), any());
        verify(agentService, never()).judgeContextPrecision(any(), any());
        verify(agentService, never()).judgeContextRecall(any(), any(), any());
    }

    @Test
    void measure_ShouldSkipTheScoresThatNeedAnExpectedAnswer_WhenTheItemHasNone() {
        final Map<String, Double> scores = measure(EvaluationItem.builder()
                .id("item-2")
                .input(QUESTION)
                .build(), answer, 0.6);

        assertEquals(List.of("context-precision", "groundedness", "refused"), scores.keySet().stream().sorted().toList());
        verify(agentService, never()).judgeAnswerRecall(any(), any(), any());
        verify(agentService, never()).judgeContextRecall(any(), any(), any());
    }

    @Test
    void measure_ShouldSkipKeyTermRecall_WhenTheExpectedAnswerNamesNoIdentifier() {
        final EvaluationItem plainItem = EvaluationItem.builder().id("item-3").input(QUESTION)
                .expectedOutput("It is a well-known framework, e.g. for version 3.2.5.").build();
        when(ragService.embedTexts(anyList())).thenReturn(Mono.just(List.of(List.of(1f, 0f), List.of(1f, 0f))));

        assertTrue(measure(item, answer, 0.6).containsKey("key-term-recall"));
        assertFalse(measure(plainItem, answer, 0.6).containsKey("key-term-recall"));
    }

    @Test
    void measure_ShouldKeepTheOtherScores_WhenAMeasurementFailsOrGivesNothing() {
        when(agentService.judgeAnswerRecall(anyString(), anyString(), anyString())).thenReturn(Mono.empty());
        when(agentService.judgeGroundedness(anyString(), anyString(), anyString()))
                .thenReturn(Mono.error(new IllegalStateException("model not available")));
        when(agentService.isRefusal(answer)).thenReturn(true);

        final Map<String, Double> scores = measure(item, answer, 0.6);

        assertEquals(List.of("context-precision", "context-recall", "key-term-recall", "refused", "semantic-similarity"),
                scores.keySet().stream().sorted().toList());
        assertEquals(1.0, scores.get("refused"), PRECISION);
    }

    @Test
    void measureSimilarityBaseline_ShouldAverageEveryPairOfDistinctExpectedAnswers() {
        when(ragService.embedTexts(List.of("Two.", "A framework.", "Three.")))
                .thenReturn(Mono.just(List.of(List.of(1f, 0f), List.of(0f, 2f), List.of(3f, 0f))));

        assertEquals(1.0 / 3, evaluationMetricsService.measureSimilarityBaseline(List.of(
                expecting("Two."), expecting(null), expecting(" "), expecting("A framework."), expecting("Two."), expecting("Three.")))
                .block(), PRECISION);
    }

    @Test
    void measureSimilarityBaseline_ShouldBeZero_WhenItCannotBeMeasured() {
        when(ragService.embedTexts(List.of("Two."))).thenReturn(Mono.just(List.of(List.of(1f, 0f))));
        when(ragService.embedTexts(List.of("Two.", "Three."))).thenReturn(Mono.error(new IllegalStateException("model not available")));

        assertEquals(0.0, evaluationMetricsService.measureSimilarityBaseline(List.of(expecting("Two."))).block(), PRECISION);
        assertEquals(0.0, evaluationMetricsService.measureSimilarityBaseline(List.of(expecting("Two."), expecting("Three."))).block(), PRECISION);
    }

    private EvaluationItem expecting(final String expectedAnswer) {
        return EvaluationItem.builder().expectedOutput(expectedAnswer).build();
    }
}
