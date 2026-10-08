package com.mtcc.oracle.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.oracle.config.EvaluationProperties;
import com.mtcc.oracle.entity.AgentAnswer;
import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.entity.EvaluationScore;
import com.mtcc.oracle.config.AgentProperties;
import com.mtcc.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class EvaluationMetricsService {

    private static final String SOURCES_SEPARATOR = "\n\n---\n\n";
    private static final String CUT_MARKER = "\n...\n";
    private static final int MIN_SOURCE_CHARS = 200;
    private static final Pattern KEY_TERM_PATTERN = Pattern.compile(
            "@\\w{2,}|\\w*[a-z0-9][A-Z]\\w*|[\\w-]{2,}(?:\\.[\\w-]{2,})+|\\w+(?:-\\w+){2,}");

    private final AgentService agentService;
    private final RagService ragService;
    private final AgentProperties agentProperties;
    private final EvaluationProperties evaluationProperties;

    public Flux<EvaluationScore> measure(final EvaluationItem item, final AgentAnswer answer, final double similarityBaseline) {
        final Mono<Double> answerRecall = measureAnswerRecall(item, answer).cache();
        final Mono<Double> similarity = measureSimilarity(item, answer, similarityBaseline).cache();
        final Mono<Double> correctness = Mono.zip(answerRecall, similarity, (judged, similar) -> (judged + similar) / 2);

        return Flux.concat(
                toScore("answer-recall", answerRecall),
                toScore("semantic-similarity", similarity),
                toScore("answer-correctness", correctness),
                toScore("key-term-recall", measureKeyTermRecall(item, answer)),
                toScore("groundedness", measureGroundedness(item, answer)),
                toScore("context-precision", measureContextPrecision(item, answer)),
                toScore("context-recall", measureContextRecall(item, answer)),
                toScore("refused", Mono.just(agentService.isRefusal(answer) ? 1.0 : 0.0)));
    }

    public Mono<Double> measureSimilarityBaseline(final List<EvaluationItem> items) {
        final List<String> expectedAnswers = items.stream()
                .map(EvaluationItem::getExpectedOutput)
                .filter(StringUtils::isNotBlank)
                .distinct()
                .toList();

        return ragService.embedTexts(expectedAnswers)
                .map(this::getAverageSimilarity)
                .onErrorReturn(0.0);
    }

    private Mono<EvaluationScore> toScore(final String name, final Mono<Double> measurement) {
        return measurement
                .map(value -> EvaluationScore.builder().name(name).value(Math.max(0.0, Math.min(1.0, value))).build())
                .onErrorResume(error -> Mono.empty());
    }

    private Mono<Double> measureAnswerRecall(final EvaluationItem item, final AgentAnswer answer) {
        return StringUtils.isBlank(item.getExpectedOutput()) ?
                Mono.empty() :
                agentService.judgeAnswerRecall(item.getInput(), item.getExpectedOutput(), answer.getText());
    }

    private Mono<Double> measureSimilarity(final EvaluationItem item, final AgentAnswer answer, final double baseline) {
        if (StringUtils.isBlank(item.getExpectedOutput())) {
            return Mono.empty();
        }

        return ragService.embedTexts(List.of(item.getExpectedOutput(), answer.getText()))
                .map(vectors -> getCosineSimilarity(vectors.get(0), vectors.get(1)))
                .map(similarity -> baseline < 1 ? (similarity - baseline) / (1 - baseline) : 0.0);
    }

    private Mono<Double> measureKeyTermRecall(final EvaluationItem item, final AgentAnswer answer) {
        final String answerText = answer.getText().toLowerCase(Locale.ROOT);
        final List<String> keyTerms = KEY_TERM_PATTERN.matcher(StringUtils.defaultString(item.getExpectedOutput())).results()
                .map(MatchResult::group)
                .map(keyTerm -> keyTerm.toLowerCase(Locale.ROOT))
                .distinct()
                .toList();

        return keyTerms.isEmpty() ?
                Mono.empty() :
                Mono.just((double) keyTerms.stream().filter(answerText::contains).count() / keyTerms.size());
    }

    private Mono<Double> measureGroundedness(final EvaluationItem item, final AgentAnswer answer) {
        return answer.getSources().isEmpty() ?
                Mono.empty() :
                agentService.judgeGroundedness(item.getInput(), getNumberedSources(answer), answer.getText());
    }

    private Mono<Double> measureContextPrecision(final EvaluationItem item, final AgentAnswer answer) {
        return answer.getSources().isEmpty() ?
                Mono.empty() :
                agentService.judgeContextPrecision(item.getInput(), getNumberedSources(answer));
    }

    private Mono<Double> measureContextRecall(final EvaluationItem item, final AgentAnswer answer) {
        return StringUtils.isBlank(item.getExpectedOutput()) || answer.getSources().isEmpty() ?
                Mono.empty() :
                agentService.judgeContextRecall(item.getInput(), item.getExpectedOutput(), getNumberedSources(answer));
    }

    private String getNumberedSources(final AgentAnswer answer) {
        final List<InformationChunk> sources = answer.getSources();
        final int maxSourceChars = Math.max(MIN_SOURCE_CHARS, Math.min(
                agentProperties.getSearchMaxChunkChars(), evaluationProperties.getMaxSourcesChars() / sources.size()));

        return IntStream.range(0, sources.size())
                .mapToObj(position -> "[" + (position + 1) + "]\n"
                        + keepHeadAndTail(sources.get(position).toEmbeddingText(), maxSourceChars))
                .collect(Collectors.joining(SOURCES_SEPARATOR));
    }

    private String keepHeadAndTail(final String text, final int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }

        final int tailChars = (maxChars - CUT_MARKER.length()) / 3;
        final int headChars = maxChars - CUT_MARKER.length() - tailChars;

        return text.substring(0, headChars) + CUT_MARKER + text.substring(text.length() - tailChars);
    }

    private double getAverageSimilarity(final List<List<Float>> vectors) {
        double total = 0;
        int pairs = 0;

        for (int first = 0; first < vectors.size(); first++) {
            for (int second = first + 1; second < vectors.size(); second++) {
                total += getCosineSimilarity(vectors.get(first), vectors.get(second));
                pairs++;
            }
        }

        return pairs == 0 ? 0.0 : Math.max(0.0, total / pairs);
    }

    private double getCosineSimilarity(final List<Float> first, final List<Float> second) {
        double dotProduct = 0;
        double firstNorm = 0;
        double secondNorm = 0;

        for (int index = 0; index < Math.min(first.size(), second.size()); index++) {
            dotProduct += first.get(index) * second.get(index);
            firstNorm += first.get(index) * first.get(index);
            secondNorm += second.get(index) * second.get(index);
        }

        return firstNorm == 0 || secondNorm == 0 ? 0.0 : dotProduct / Math.sqrt(firstNorm * secondNorm);
    }
}
