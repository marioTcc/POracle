package com.mtcc.rag.serviceimpl;

import com.google.common.util.concurrent.Futures;
import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.rag.config.QdrantClientProperties;
import com.mtcc.rag.util.KeywordVectorizer;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QdrantServiceImplTest {

    private static final String COLLECTION = "projects_collection";
    private static final String CHUNK_ID = "0e2f3c5e-8a51-3d4b-9f0a-3b1c2d4e5f60";

    private static final Map<String, JsonWithInt.Value> PAYLOAD = Map.of(
                    "projectName", "spring-ai", "chunkId", CHUNK_ID, "filename", "OpenAiApi.java",
                    "filePath", "models/openai/OpenAiApi.java", "language", "JAVA", "informationType", "CODE",
                    "text", "void call() {}", "chunkingStrategy", "code", "context", "org.example.OpenAiApi",
                    "ingestionId", "run-1").entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, field -> ValueFactory.value(field.getValue())));

    @Mock
    private QdrantClient qdrantClient;
    @Mock
    private QdrantClientProperties properties;
    @InjectMocks
    private QdrantServiceImpl qdrantService;

    private final InformationChunk chunk = InformationChunk.buildChunk(
                    "spring-ai", "README.md", "MD", InformationType.DOC, "Some documentation.", "README.md", "text")
            .toBuilder().ingestionId("run-2").build();

    @BeforeEach
    void setUp() {
        when(properties.getQdrantSearchTopK()).thenReturn(8);
        when(properties.getQdrantSearchMinScore()).thenReturn(0.0);
        when(properties.getQdrantSearchHybridEnabled()).thenReturn(true);
        when(properties.getQdrantSearchPrefetchLimit()).thenReturn(20);
        final Points.UpdateResult updated = Points.UpdateResult.getDefaultInstance();
        final Collections.CollectionOperationResponse done =
                Collections.CollectionOperationResponse.newBuilder().setResult(true).build();

        when(qdrantClient.upsertAsync(anyString(), anyList())).thenReturn(Futures.immediateFuture(updated));
        when(qdrantClient.deleteAsync(anyString(), any(Points.Filter.class))).thenReturn(Futures.immediateFuture(updated));
        when(qdrantClient.createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Futures.immediateFuture(updated));
        when(qdrantClient.createCollectionAsync(any(Collections.CreateCollection.class))).thenReturn(Futures.immediateFuture(done));
        when(qdrantClient.updateCollectionAsync(any(Collections.UpdateCollection.class))).thenReturn(Futures.immediateFuture(done));
        when(qdrantClient.deleteCollectionAsync(anyString())).thenReturn(Futures.immediateFuture(done));
    }

    private void collectionExists(final String collection, final boolean exists) {
        when(qdrantClient.collectionExistsAsync(collection)).thenReturn(Futures.immediateFuture(exists));
    }

    private void collectionStoresVectorsOfSize(final int size) {
        collectionStores(Collections.CollectionParams.newBuilder()
                .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                        .setParamsMap(Collections.VectorParamsMap.newBuilder()
                                .putMap("dense", Collections.VectorParams.newBuilder().setSize(size).build())))
                .setSparseVectorsConfig(Collections.SparseVectorConfig.newBuilder()
                        .putMap("keywords", Collections.SparseVectorParams.getDefaultInstance()))
                .build());
    }

    private void collectionStores(final Collections.CollectionParams collectionParams) {
        collectionExists(COLLECTION, true);
        when(qdrantClient.getCollectionInfoAsync(COLLECTION)).thenReturn(Futures.immediateFuture(
                Collections.CollectionInfo.newBuilder()
                        .setConfig(Collections.CollectionConfig.newBuilder().setParams(collectionParams))
                        .build()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertChunkBatch_ShouldStoreEachChunkWithItsVectorsAndPayload() {
        qdrantService.upsertChunkBatch(List.of(Tuples.of(chunk, List.of(0.1f, 0.2f)))).block();

        final ArgumentCaptor<List<Points.PointStruct>> points = ArgumentCaptor.forClass(List.class);
        verify(qdrantClient).upsertAsync(eq(COLLECTION), points.capture());
        final Points.PointStruct point = points.getValue().get(0);

        assertEquals(chunk.getChunkId(), point.getId().getUuid());
        final Map<String, Points.Vector> vectors = point.getVectors().getVectors().getVectorsMap();
        assertEquals(List.of(0.1f, 0.2f), vectors.get("dense").getDataList());
        assertEquals(KeywordVectorizer.ofPassage(chunk.toEmbeddingText()).getIndices(),
                vectors.get("keywords").getIndices().getDataList());
        assertEquals(KeywordVectorizer.ofPassage(chunk.toEmbeddingText()).getValues(), vectors.get("keywords").getDataList());
        assertEquals("spring-ai", point.getPayloadMap().get("projectName").getStringValue());
        assertEquals("DOC", point.getPayloadMap().get("informationType").getStringValue());
        assertEquals("Some documentation.", point.getPayloadMap().get("text").getStringValue());
        assertEquals("run-2", point.getPayloadMap().get("ingestionId").getStringValue());
    }

    @Test
    void searchSimilarChunks_ShouldFuseTheVectorAndKeywordSearchesAndMapEachHitToAChunkWithItsScore() {
        when(qdrantClient.queryAsync(any(Points.QueryPoints.class))).thenReturn(Futures.immediateFuture(List.of(
                Points.ScoredPoint.newBuilder().setScore(0.83f).putAllPayload(PAYLOAD).build(),
                Points.ScoredPoint.newBuilder().setScore(0.5f).build())));

        when(properties.getQdrantSearchMinScore()).thenReturn(0.4);

        final List<RetrievedChunk> chunks = qdrantService.searchSimilarChunks("NimbusJwtDecoder", List.of(0.1f), "spring-ai").block();

        assertEquals(1, chunks.size());
        assertEquals(0.83, chunks.get(0).getScore(), 0.0001);
        final InformationChunk found = chunks.get(0).getChunk();
        assertEquals("spring-ai", found.getProjectName());
        assertEquals("models/openai/OpenAiApi.java", found.getFilePath());
        assertEquals(InformationType.CODE, found.getInformationType());
        assertEquals("org.example.OpenAiApi", found.getContext());
        assertEquals("void call() {}", found.getText());
        assertEquals("run-1", found.getIngestionId());

        final Points.QueryPoints search = capturedQuery();
        assertEquals(COLLECTION, search.getCollectionName());
        assertEquals(8, search.getLimit());
        assertEquals(Points.Fusion.RRF, search.getQuery().getFusion());
        assertEquals(2, search.getPrefetchCount());

        final Points.PrefetchQuery vectorSearch = search.getPrefetch(0);
        assertEquals("dense", vectorSearch.getUsing());
        assertEquals(20, vectorSearch.getLimit());
        assertEquals(0.4f, vectorSearch.getScoreThreshold());
        assertEquals(List.of(0.1f), vectorSearch.getQuery().getNearest().getDense().getDataList());
        assertEquals("spring-ai", vectorSearch.getFilter().getMust(0).getField().getMatch().getKeyword());

        final Points.PrefetchQuery keywordSearch = search.getPrefetch(1);
        assertEquals("keywords", keywordSearch.getUsing());
        assertFalse(keywordSearch.hasScoreThreshold());
        assertEquals(KeywordVectorizer.ofQuery("NimbusJwtDecoder").getIndices(),
                keywordSearch.getQuery().getNearest().getSparse().getIndicesList());
        assertEquals("projectName", keywordSearch.getFilter().getMust(0).getField().getKey());
    }

    @Test
    void searchSimilarChunks_ShouldSearchEveryProjectWithoutThreshold_WhenNoneIsConfigured() {
        when(qdrantClient.queryAsync(any(Points.QueryPoints.class))).thenReturn(Futures.immediateFuture(List.of()));

        assertTrue(qdrantService.searchSimilarChunks("authentication", List.of(0.1f), null).block().isEmpty());

        final Points.QueryPoints search = capturedQuery();
        assertFalse(search.getPrefetch(0).hasFilter());
        assertFalse(search.getPrefetch(0).hasScoreThreshold());
        assertFalse(search.getPrefetch(1).hasFilter());
    }

    @Test
    void searchSimilarChunks_ShouldOnlySearchTheVectors_WhenTheHybridSearchIsOffOrTheQueryHasNoKeyword() {
        when(qdrantClient.queryAsync(any(Points.QueryPoints.class))).thenReturn(Futures.immediateFuture(List.of()));
        when(properties.getQdrantSearchMinScore()).thenReturn(0.4);

        qdrantService.searchSimilarChunks("?", List.of(0.1f), "spring-ai").block();

        final Points.QueryPoints search = capturedQuery();
        assertEquals(0, search.getPrefetchCount());
        assertEquals("dense", search.getUsing());
        assertEquals(List.of(0.1f), search.getQuery().getNearest().getDense().getDataList());
        assertEquals(8, search.getLimit());
        assertEquals(0.4f, search.getScoreThreshold());
        assertEquals("spring-ai", search.getFilter().getMust(0).getField().getMatch().getKeyword());

        when(properties.getQdrantSearchHybridEnabled()).thenReturn(false);
        qdrantService.searchSimilarChunks("authentication", List.of(0.1f), null).block();

        final ArgumentCaptor<Points.QueryPoints> searches = ArgumentCaptor.forClass(Points.QueryPoints.class);
        verify(qdrantClient, org.mockito.Mockito.times(2)).queryAsync(searches.capture());
        assertEquals(0, searches.getAllValues().get(1).getPrefetchCount());
        assertFalse(searches.getAllValues().get(1).hasFilter());
    }

    private Points.QueryPoints capturedQuery() {
        final ArgumentCaptor<Points.QueryPoints> search = ArgumentCaptor.forClass(Points.QueryPoints.class);
        verify(qdrantClient).queryAsync(search.capture());
        return search.getValue();
    }

    @Test
    void findChunksByType_ShouldMapTheStoredChunks() {
        when(qdrantClient.scrollAsync(any(Points.ScrollPoints.class))).thenReturn(Futures.immediateFuture(
                Points.ScrollResponse.newBuilder()
                        .addResult(Points.RetrievedPoint.newBuilder().putAllPayload(PAYLOAD))
                        .build()));

        final List<InformationChunk> chunks = qdrantService.findChunksByType(InformationType.CODE, 10).block();

        assertEquals(List.of("void call() {}"), chunks.stream().map(InformationChunk::getText).toList());

        final ArgumentCaptor<Points.ScrollPoints> scroll = ArgumentCaptor.forClass(Points.ScrollPoints.class);
        verify(qdrantClient).scrollAsync(scroll.capture());
        assertEquals(10, scroll.getValue().getLimit());
        assertEquals("CODE", scroll.getValue().getFilter().getMust(0).getField().getMatch().getKeyword());
    }

    @Test
    void getChunk_ShouldReturnTheStoredChunk_OrNothingWhenItDoesNotExist() {
        when(qdrantClient.retrieveAsync(eq(COLLECTION), any(Points.PointId.class), eq(true), eq(false), isNull()))
                .thenReturn(Futures.immediateFuture(List.of(Points.RetrievedPoint.newBuilder().putAllPayload(PAYLOAD).build())))
                .thenReturn(Futures.immediateFuture(List.of()));

        assertEquals("void call() {}", qdrantService.getChunk(CHUNK_ID).block().getText());
        assertNull(qdrantService.getChunk(CHUNK_ID).block());
    }

    @Test
    void countChunksByProject_ShouldReturnTheNumberOfChunksOfEachProject() {
        when(qdrantClient.facetAsync(any(Points.FacetCounts.class))).thenReturn(Futures.immediateFuture(List.of(
                Points.FacetHit.newBuilder().setValue(Points.FacetValue.newBuilder().setStringValue("spring-security")).setCount(300).build(),
                Points.FacetHit.newBuilder().setValue(Points.FacetValue.newBuilder().setStringValue("spring-ai")).setCount(120).build())));

        assertEquals(Map.of("spring-ai", 120L, "spring-security", 300L), qdrantService.countChunksByProject().block());
    }

    @Test
    void enableIndexing_ShouldRestoreTheThresholdAndCreateThePayloadIndexes() {
        when(properties.getQdrantIndexingThreshold()).thenReturn(20000);

        qdrantService.enableIndexing().block();

        final ArgumentCaptor<Collections.UpdateCollection> update = ArgumentCaptor.forClass(Collections.UpdateCollection.class);
        verify(qdrantClient).updateCollectionAsync(update.capture());
        assertEquals(20000, update.getValue().getOptimizersConfig().getIndexingThreshold());
        for (final String field : List.of("projectName", "informationType")) {
            verify(qdrantClient).createPayloadIndexAsync(eq(COLLECTION), eq(field), eq(Collections.PayloadSchemaType.Keyword),
                    isNull(), eq(true), isNull(), isNull());
        }
    }

    @Test
    void disableIndexing_ShouldOnlyUpdateTheCollection() {
        qdrantService.disableIndexing().block();

        final ArgumentCaptor<Collections.UpdateCollection> update = ArgumentCaptor.forClass(Collections.UpdateCollection.class);
        verify(qdrantClient).updateCollectionAsync(update.capture());
        assertEquals(0, update.getValue().getOptimizersConfig().getIndexingThreshold());
        verify(qdrantClient, never()).createPayloadIndexAsync(anyString(), anyString(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteCollection_ShouldTellWhetherTheCollectionWasDeleted() {
        collectionExists(COLLECTION, true);
        collectionExists("missing", false);

        assertTrue(qdrantService.deleteCollection(COLLECTION).block());
        assertFalse(qdrantService.deleteCollection("missing").block());
        verify(qdrantClient, never()).deleteCollectionAsync("missing");
    }

    @Test
    void createCollectionIfMissing_ShouldCreateTheCollection_WhenItDoesNotExist() {
        collectionExists(COLLECTION, false);

        qdrantService.createCollectionIfMissing(1024).block();

        final ArgumentCaptor<Collections.CreateCollection> collection = ArgumentCaptor.forClass(Collections.CreateCollection.class);
        verify(qdrantClient).createCollectionAsync(collection.capture());
        assertEquals(COLLECTION, collection.getValue().getCollectionName());
        final Collections.VectorParams vectors = collection.getValue().getVectorsConfig().getParamsMap().getMapOrThrow("dense");
        assertEquals(1024, vectors.getSize());
        assertEquals(Collections.Distance.Cosine, vectors.getDistance());
        assertEquals(Collections.Modifier.Idf,
                collection.getValue().getSparseVectorsConfig().getMapOrThrow("keywords").getModifier());
        assertEquals(0, collection.getValue().getOptimizersConfig().getIndexingThreshold());
    }

    @Test
    void createCollectionIfMissing_ShouldKeepTheCollection_WhenItStoresVectorsOfTheSameSize() {
        collectionStoresVectorsOfSize(384);

        qdrantService.createCollectionIfMissing(384).block();

        verify(qdrantClient, never()).createCollectionAsync(any(Collections.CreateCollection.class));
    }

    @Test
    void createCollectionIfMissing_ShouldFail_WhenTheCollectionStoresVectorsOfAnotherSize() {
        collectionStoresVectorsOfSize(384);

        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> qdrantService.createCollectionIfMissing(1024).block());

        assertTrue(error.getMessage().contains("384") && error.getMessage().contains("1024"));
        verify(qdrantClient, never()).createCollectionAsync(any(Collections.CreateCollection.class));
    }

    @Test
    void createCollectionIfMissing_ShouldFail_WhenTheCollectionWasCreatedWithoutKeywordVectors() {
        collectionStores(Collections.CollectionParams.newBuilder()
                .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                        .setParams(Collections.VectorParams.newBuilder().setSize(384)))
                .build());

        final IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> qdrantService.createCollectionIfMissing(384).block());

        assertTrue(error.getMessage().contains("without keyword vectors"), error.getMessage());
        assertTrue(error.getMessage().contains("delete the collection and ingest again"), error.getMessage());
        verify(qdrantClient, never()).createCollectionAsync(any(Collections.CreateCollection.class));
    }

    @Test
    void deleteChunksOfOtherIngestions_ShouldDeleteTheChunksOfTheProjectThatAreNotOfThisIngestion() {
        qdrantService.deleteOldIngestionChunks("spring-ai", "run-2").block();

        final ArgumentCaptor<Points.Filter> filter = ArgumentCaptor.forClass(Points.Filter.class);
        verify(qdrantClient).deleteAsync(eq(COLLECTION), filter.capture());
        assertEquals("projectName", filter.getValue().getMust(0).getField().getKey());
        assertEquals("spring-ai", filter.getValue().getMust(0).getField().getMatch().getKeyword());
        assertEquals("ingestionId", filter.getValue().getMustNot(0).getField().getKey());
        assertEquals("run-2", filter.getValue().getMustNot(0).getField().getMatch().getKeyword());
    }

    @Test
    void operations_ShouldFail_WhenTheStoreFails() {
        when(qdrantClient.collectionExistsAsync(COLLECTION))
                .thenReturn(Futures.immediateFailedFuture(new IllegalStateException("store not reachable")));

        assertThrows(IllegalStateException.class, () -> qdrantService.createCollectionIfMissing(384).block());
    }
}
