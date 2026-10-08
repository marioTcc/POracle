package com.mtcc.rag.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.common.logging.IngestionProgress;
import com.mtcc.rag.exception.CollectionNotFoundException;
import com.mtcc.rag.serviceimpl.interfaces.IEmbeddingServiceImpl;
import com.mtcc.rag.serviceimpl.interfaces.IRagServiceImpl;
import com.mtcc.rag.serviceimpl.interfaces.IRerankServiceImpl;
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
import reactor.util.function.Tuples;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RagServiceTest {

    private static final List<Float> VECTOR = List.of(0.1f, 0.2f);

    @Mock
    private IRagServiceImpl ragServiceImpl;
    @Mock
    private IEmbeddingServiceImpl embeddingService;
    @Mock
    private IRerankServiceImpl rerankService;
    @Mock
    private IngestionProgress ingestionProgress;
    @InjectMocks
    private RagService ragService;

    private final InformationChunk chunk = InformationChunk.buildChunk(
            "spring-ai", "README.md", "MD", InformationType.DOC, "Some documentation.", "README.md", "text");

    @BeforeEach
    void setUp() {
        when(embeddingService.generateQueryEmbedding(anyString())).thenReturn(Mono.just(VECTOR));
        when(embeddingService.generatePassageEmbeddings(anyList())).thenReturn(Mono.just(List.of(Tuples.of(chunk, VECTOR))));
        when(ragServiceImpl.upsertChunkBatch(anyList())).thenReturn(Mono.empty());
    }

    @Test
    void searchChunks_ShouldEmbedTheQuerySearchTheProjectAndRerankTheResults() {
        final List<RetrievedChunk> retrieved = List.of(RetrievedChunk.builder().chunk(chunk).score(0.9).build());
        final List<RetrievedChunk> reranked = List.of(RetrievedChunk.builder().chunk(chunk).score(0.4).build());
        when(ragServiceImpl.searchSimilarChunks("what is spring-ai", VECTOR, "spring-ai")).thenReturn(Mono.just(retrieved));
        when(rerankService.rerank("what is spring-ai", retrieved)).thenReturn(Mono.just(reranked));

        assertEquals(reranked, ragService.searchChunks("what is spring-ai", "spring-ai", null).block());
        verify(embeddingService).generateQueryEmbedding("what is spring-ai");
        verify(embeddingService, never()).generatePassageEmbeddings(anyList());
    }

    @Test
    void searchChunks_ShouldRankTheResultsAgainstTheQuestion_WhenThereIsOne() {
        final List<RetrievedChunk> retrieved = List.of(RetrievedChunk.builder().chunk(chunk).score(0.9).build());
        final List<RetrievedChunk> reranked = List.of(RetrievedChunk.builder().chunk(chunk).score(0.4).build());
        when(ragServiceImpl.searchSimilarChunks("tool calling", VECTOR, "spring-ai")).thenReturn(Mono.just(retrieved));
        when(rerankService.rerank("How do I let the model call my code?", retrieved)).thenReturn(Mono.just(reranked));

        assertEquals(reranked,
                ragService.searchChunks("tool calling", "spring-ai", "How do I let the model call my code?").block());
        verify(embeddingService).generateQueryEmbedding("tool calling");
    }

    @Test
    void ingestChunksBatch_ShouldEmbedEachChunkAsAPassageAndStoreTheBatch() {
        final List<InformationChunk> ingested = ragService.ingestChunksBatch(List.of(chunk)).collectList().block();

        assertEquals(List.of(chunk), ingested);
        verify(embeddingService).generatePassageEmbeddings(List.of(chunk));
        verify(ragServiceImpl).upsertChunkBatch(List.of(Tuples.of(chunk, VECTOR)));
        verify(ingestionProgress).chunksUpserted("spring-ai", 1);
    }

    @Test
    void ingestChunksBatch_ShouldDropTheBatchAndCountItAsFailed_WhenTheStoreFails() {
        when(ragServiceImpl.upsertChunkBatch(anyList())).thenReturn(Mono.error(new IllegalStateException("store not reachable")));

        final List<InformationChunk> ingested = ragService.ingestChunksBatch(List.of(chunk)).collectList().block();

        assertTrue(ingested.isEmpty());
        verify(ingestionProgress).chunksFailed("spring-ai", 1);
        verify(ingestionProgress, never()).chunksUpserted(anyString(), Mockito.anyInt());
    }

    @Test
    void embedTexts_ShouldEmbedThePlainTexts() {
        when(embeddingService.generateTextEmbeddings(List.of("first", "second")))
                .thenReturn(Mono.just(List.of(List.of(1f, 0f), List.of(0f, 1f))));

        assertEquals(List.of(List.of(1f, 0f), List.of(0f, 1f)), ragService.embedTexts(List.of("first", "second")).block());
        verify(embeddingService, never()).generatePassageEmbeddings(anyList());
    }

    @Test
    void deleteCollection_ShouldFail_WhenTheCollectionDoesNotExist() {
        when(ragServiceImpl.deleteCollection("missing")).thenReturn(Mono.just(false));
        when(ragServiceImpl.deleteCollection("projects_collection")).thenReturn(Mono.just(true));

        assertThrows(CollectionNotFoundException.class, () -> ragService.deleteCollection("missing").block());
        ragService.deleteCollection("projects_collection").block();
    }

    @Test
    void disableAndEnableIndexing_ShouldNotFail_WhenTheStoreIsNotReachable() {
        when(ragServiceImpl.disableIndexing()).thenReturn(Mono.error(new IllegalStateException("store not reachable")));
        when(ragServiceImpl.enableIndexing()).thenReturn(Mono.error(new IllegalStateException("store not reachable")));

        ragService.disableIndexing().block();
        ragService.enableIndexing().block();

        verify(ragServiceImpl).disableIndexing();
        verify(ragServiceImpl).enableIndexing();
    }

    @Test
    void ingestChunksBatch_ShouldStoreAndCountThePairsReturnedByTheEmbedding() {
        final InformationChunk otherChunk = InformationChunk.buildChunk(
                "spring-ai", "pom.xml", "XML", InformationType.CONFIG, "Some configuration.", "pom.xml", "text");
        final List<Float> otherVector = List.of(0.9f, 0.8f);
        when(embeddingService.generatePassageEmbeddings(List.of(chunk, otherChunk)))
                .thenReturn(Mono.just(List.of(Tuples.of(chunk, VECTOR), Tuples.of(otherChunk, otherVector))));

        final List<InformationChunk> ingested = ragService.ingestChunksBatch(List.of(chunk, otherChunk)).collectList().block();

        assertEquals(List.of(chunk, otherChunk), ingested);
        verify(ragServiceImpl).upsertChunkBatch(List.of(Tuples.of(chunk, VECTOR), Tuples.of(otherChunk, otherVector)));
        verify(ingestionProgress).chunksUpserted("spring-ai", 2);
    }

    @Test
    void ingestChunksBatch_ShouldDropTheBatchAndCountItAsFailed_WhenTheEmbeddingFails() {
        when(embeddingService.generatePassageEmbeddings(anyList())).thenReturn(Mono.error(new IllegalStateException("model not available")));

        assertTrue(ragService.ingestChunksBatch(List.of(chunk)).collectList().block().isEmpty());
        verify(ingestionProgress).chunksFailed("spring-ai", 1);
        verify(ragServiceImpl, never()).upsertChunkBatch(anyList());
    }

    @Test
    void prepareCollection_ShouldCreateTheCollectionWithTheSizeOfTheEmbeddings() {
        when(embeddingService.getDimensions()).thenReturn(Mono.just(1024));
        when(ragServiceImpl.createCollectionIfMissing(1024)).thenReturn(Mono.empty());

        ragService.prepareCollection().block();

        verify(ragServiceImpl).createCollectionIfMissing(1024);
    }

    @Test
    void deleteChunksOfOtherIngestions_ShouldNotFail_WhenTheStoreIsNotReachable() {
        when(ragServiceImpl.deleteOldIngestionChunks("spring-ai", "run-1"))
                .thenReturn(Mono.error(new IllegalStateException("store not reachable")));

        ragService.deleteOldIngestionChunks("spring-ai", "run-1").block();

        verify(ragServiceImpl).deleteOldIngestionChunks("spring-ai", "run-1");
    }
}
