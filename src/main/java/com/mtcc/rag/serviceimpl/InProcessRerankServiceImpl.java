package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.rag.config.RagProperties;
import com.mtcc.rag.serviceimpl.interfaces.IRerankServiceImpl;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.scoring.ScoringModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "RERANK.PROVIDER", havingValue = "in-process")
public class InProcessRerankServiceImpl implements IRerankServiceImpl {

    private final ScoringModel rerankModel;
    private final RagProperties ragProperties;
    private final Scheduler embeddingScheduler;

    @Override
    public Mono<List<RetrievedChunk>> rerank(final String query, final List<RetrievedChunk> candidates) {
        if (candidates.isEmpty()) {
            return Mono.just(candidates);
        }

        final List<TextSegment> passages = candidates.stream()
                .map(candidate -> TextSegment.from(candidate.getChunk().toEmbeddingText()))
                .toList();

        return Mono.fromCallable(() -> rerankModel.scoreAll(passages, query).content())
                .map(scores -> withScores(candidates, scores))
                .doOnError(error -> log.warn("Cannot rerank {} search results: {}", candidates.size(), error.toString()))
                .subscribeOn(embeddingScheduler);
    }

    private List<RetrievedChunk> withScores(final List<RetrievedChunk> candidates, final List<Double> scores) {
        if (scores.size() != candidates.size()) {
            throw new IllegalStateException("The rerank model returned " + scores.size() + " scores for "
                    + candidates.size() + " search results");
        }

        return IntStream.range(0, candidates.size())
                .mapToObj(position -> RetrievedChunk.builder()
                        .chunk(candidates.get(position).getChunk())
                        .score(scores.get(position))
                        .build())
                .sorted(Comparator.comparingDouble(RetrievedChunk::getScore).reversed())
                .limit(ragProperties.getRerankTopK())
                .toList();
    }
}
