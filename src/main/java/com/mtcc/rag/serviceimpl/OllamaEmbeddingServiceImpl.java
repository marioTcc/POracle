package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.rag.config.OllamaClientProperties;
import com.mtcc.rag.serviceimpl.interfaces.IEmbeddingServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.stream.IntStream;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "EMBEDDING.PROVIDER", havingValue = "ollama", matchIfMissing = true)
public class OllamaEmbeddingServiceImpl implements IEmbeddingServiceImpl {

    private final EmbeddingModel ollamaEmbeddingModel;
    private final OllamaClientProperties ollamaClientProperties;
    private final Scheduler embeddingScheduler;

    @Override
    public Mono<List<Float>> generateQueryEmbedding(final String query) {
        return generateEmbeddings(List.of(ollamaClientProperties.getOllamaEmbeddingQueryPrefix() + query))
                .map(embeddings -> embeddings.get(0))
                .doOnError(error -> log.warn("Cannot embed the query: {}", error.toString()));
    }

    @Override
    public Mono<List<Tuple2<InformationChunk, List<Float>>>> generatePassageEmbeddings(final List<InformationChunk> chunks) {
        return generateEmbeddings(chunks.stream()
                .map(chunk -> ollamaClientProperties.getOllamaEmbeddingPassagePrefix() + chunk.toEmbeddingText())
                .toList())
                .map(vectors -> pair(chunks, vectors))
                .doOnError(error -> log.warn("Cannot embed a batch of {} chunks: {}", chunks.size(), error.toString()));
    }

    private List<Tuple2<InformationChunk, List<Float>>> pair(final List<InformationChunk> chunks, final List<List<Float>> vectors) {
        if (vectors.size() != chunks.size()) {
            throw new IllegalStateException(
                    "The embedding model returned " + vectors.size() + " vectors for " + chunks.size() + " chunks");
        }

        return IntStream.range(0, chunks.size())
                .mapToObj(index -> Tuples.of(chunks.get(index), vectors.get(index)))
                .toList();
    }

    @Override
    public Mono<List<List<Float>>> generateTextEmbeddings(final List<String> texts) {
        return generateEmbeddings(texts.stream()
                .map(text -> ollamaClientProperties.getOllamaEmbeddingPassagePrefix() + text)
                .toList())
                .map(vectors -> {
                    if (vectors.size() != texts.size()) {
                        throw new IllegalStateException(
                                "The embedding model returned " + vectors.size() + " vectors for " + texts.size() + " texts");
                    }
                    return vectors;
                })
                .doOnError(error -> log.warn("Cannot embed {} texts: {}", texts.size(), error.toString()));
    }

    @Override
    public Mono<Integer> getDimensions() {
        return Mono.fromCallable(ollamaEmbeddingModel::dimension)
                .subscribeOn(embeddingScheduler);
    }

    private Mono<List<List<Float>>> generateEmbeddings(final List<String> texts) {
        return Mono.fromCallable(() ->
                        ollamaEmbeddingModel.embedAll(texts.stream().map(TextSegment::from).toList())
                                .content().stream()
                                .map(Embedding::vectorAsList)
                                .toList())
                .subscribeOn(embeddingScheduler);
    }
}
