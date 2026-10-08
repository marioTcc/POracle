package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.rag.config.OllamaClientProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.scheduler.Schedulers;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OllamaEmbeddingServiceImplTest {

    @Mock
    private OllamaClientProperties ollamaClientProperties;

    private final List<List<String>> embeddedBatches = new CopyOnWriteArrayList<>();

    private final EmbeddingModel embeddingModel = new EmbeddingModel() {
        @Override
        public Response<List<Embedding>> embedAll(final List<TextSegment> textSegments) {
            embeddedBatches.add(textSegments.stream().map(TextSegment::text).toList());
            return Response.from(textSegments.stream()
                    .map(textSegment -> Embedding.from(new float[]{textSegment.text().length(), 0.5f}))
                    .toList());
        }
    };

    private final InformationChunk readme = InformationChunk.buildChunk(
            "spring-ai", "README.md", "MD", InformationType.DOC, "Some documentation.", "README.md", "text");
    private final InformationChunk pom = InformationChunk.buildChunk(
            "spring-ai", "pom.xml", "XML", InformationType.CONFIG, "Some longer configuration.", "pom.xml", "text");

    private OllamaEmbeddingServiceImpl embeddingService;

    @BeforeEach
    void setUp() {
        when(ollamaClientProperties.getOllamaEmbeddingQueryPrefix()).thenReturn("Query:");
        when(ollamaClientProperties.getOllamaEmbeddingPassagePrefix()).thenReturn("");
        embeddingService = new OllamaEmbeddingServiceImpl(embeddingModel, ollamaClientProperties, Schedulers.immediate());
    }

    @Test
    void generateQueryEmbedding_ShouldEmbedTheQueryWithItsPrefix() {
        assertEquals(List.of(9f, 0.5f), embeddingService.generateQueryEmbedding("abc").block());
        assertEquals(List.of(List.of("Query:abc")), embeddedBatches);
    }

    @Test
    void generatePassageEmbeddings_ShouldEmbedTheChunksInOneCallAndReturnEachChunkWithItsVector() {
        when(ollamaClientProperties.getOllamaEmbeddingPassagePrefix()).thenReturn("Passage:");

        final List<Tuple2<InformationChunk, List<Float>>> embeddings =
                embeddingService.generatePassageEmbeddings(List.of(readme, pom)).block();

        final String readmeText = "Passage:" + readme.toEmbeddingText();
        final String pomText = "Passage:" + pom.toEmbeddingText();
        assertEquals(List.of(List.of(readmeText, pomText)), embeddedBatches);
        assertEquals(List.of(Tuples.of(readme, List.of((float) readmeText.length(), 0.5f)),
                Tuples.of(pom, List.of((float) pomText.length(), 0.5f))), embeddings);
    }

    @Test
    void generatePassageEmbeddings_ShouldFail_WhenTheModelDoesNotReturnOneVectorPerChunk() {
        final EmbeddingModel incompleteModel = new EmbeddingModel() {
            @Override
            public Response<List<Embedding>> embedAll(final List<TextSegment> textSegments) {
                return Response.from(List.of(Embedding.from(new float[]{1f, 0.5f})));
            }
        };
        final OllamaEmbeddingServiceImpl incompleteService =
                new OllamaEmbeddingServiceImpl(incompleteModel, ollamaClientProperties, Schedulers.immediate());

        assertThrows(IllegalStateException.class,
                () -> incompleteService.generatePassageEmbeddings(List.of(readme, pom)).block());
    }

    @Test
    void generateTextEmbeddings_ShouldEmbedTheTextsAsPassagesInOneCall() {
        when(ollamaClientProperties.getOllamaEmbeddingPassagePrefix()).thenReturn("Passage:");

        final List<List<Float>> embeddings = embeddingService.generateTextEmbeddings(List.of("ab", "abcd")).block();

        assertEquals(List.of(List.of("Passage:ab", "Passage:abcd")), embeddedBatches);
        assertEquals(List.of(List.of(10f, 0.5f), List.of(12f, 0.5f)), embeddings);
    }

    @Test
    void getDimensions_ShouldReturnTheSizeOfTheEmbeddings() {
        assertEquals(2, embeddingService.getDimensions().block());
    }
}
