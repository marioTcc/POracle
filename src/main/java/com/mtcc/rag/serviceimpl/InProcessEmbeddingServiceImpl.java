package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.rag.serviceimpl.interfaces.IEmbeddingServiceImpl;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "EMBEDDING.PROVIDER", havingValue = "in-process")
public class InProcessEmbeddingServiceImpl implements IEmbeddingServiceImpl {

    private static final String QUERY_PREFIX = "query: ";
    private static final String PASSAGE_PREFIX = "passage: ";

    private final EmbeddingModel inProcessEmbeddingModel;
    private final Scheduler embeddingScheduler;

    @Override
    public Mono<List<Float>> generateQueryEmbedding(final String query) {
        return generateEmbedding(QUERY_PREFIX + query)
                .doOnError(error -> log.error("Cannot embed the query: {}", query, error));
    }

    @Override
    public Mono<List<Tuple2<InformationChunk, List<Float>>>> generatePassageEmbeddings(final List<InformationChunk> chunks) {
        return Flux.fromIterable(chunks)
                .flatMapSequential(chunk -> generateEmbedding(PASSAGE_PREFIX + chunk.toEmbeddingText())
                        .map(vector -> Tuples.of(chunk, vector)))
                .collectList()
                .doOnError(error -> log.warn("Cannot embed a batch of {} chunks: {}", chunks.size(), error.toString()));
    }

    @Override
    public Mono<List<List<Float>>> generateTextEmbeddings(final List<String> texts) {
        return Flux.fromIterable(texts)
                .flatMapSequential(text -> generateEmbedding(PASSAGE_PREFIX + text))
                .collectList()
                .doOnError(error -> log.warn("Cannot embed {} texts: {}", texts.size(), error.toString()));
    }

    @Override
    public Mono<Integer> getDimensions() {
        return Mono.fromCallable(inProcessEmbeddingModel::dimension)
                .subscribeOn(embeddingScheduler);
    }

    private Mono<List<Float>> generateEmbedding(final String text) {
        return Mono.fromCallable(() -> inProcessEmbeddingModel.embed(text).content().vectorAsList())
                .subscribeOn(embeddingScheduler);
    }
}
