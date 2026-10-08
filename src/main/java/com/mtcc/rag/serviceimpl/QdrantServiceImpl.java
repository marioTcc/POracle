package com.mtcc.rag.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.rag.config.QdrantClientProperties;
import com.mtcc.rag.serviceimpl.converter.PrefetchRequest;
import com.mtcc.rag.serviceimpl.converter.QdrantConverter;
import com.mtcc.rag.serviceimpl.interfaces.IRagServiceImpl;
import com.mtcc.rag.util.KeywordVector;
import com.mtcc.rag.util.KeywordVectorizer;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.mtcc.rag.serviceimpl.converter.QdrantConverter.DENSE_VECTOR;
import static com.mtcc.rag.serviceimpl.converter.QdrantConverter.INFORMATION_TYPE_FIELD;
import static com.mtcc.rag.serviceimpl.converter.QdrantConverter.INGESTION_ID_FIELD;
import static com.mtcc.rag.serviceimpl.converter.QdrantConverter.KEYWORD_VECTOR;
import static com.mtcc.rag.serviceimpl.converter.QdrantConverter.PROJECT_NAME_FIELD;
import static com.mtcc.rag.util.ListenableFutureUtil.toMono;
import static io.qdrant.client.ConditionFactory.matchKeyword;
import static io.qdrant.client.QueryFactory.fusion;
import static io.qdrant.client.QueryFactory.nearest;

@Slf4j
@Service
@RequiredArgsConstructor
public class QdrantServiceImpl implements IRagServiceImpl {

    private static final String COLLECTION_NAME = "projects_collection";
    private static final int INDEXING_DISABLED = 0;
    private static final int MAX_PROJECTS = 1000;

    private final QdrantClient qdrantClient;
    private final QdrantClientProperties qdrantClientProperties;

    private final AtomicBoolean isFirstUpsertFailure = new AtomicBoolean(true);

    @Override
    public Mono<Void> upsertChunkBatch(final List<Tuple2<InformationChunk, List<Float>>> chunksAndEmbeddings) {
        final List<Points.PointStruct> points = chunksAndEmbeddings.stream()
                .map(QdrantConverter.getInformationChunkToPointConverter().convert())
                .toList();

        return toMono(() -> qdrantClient.upsertAsync(COLLECTION_NAME, points))
                .doOnError(error -> {
                    if (isFirstUpsertFailure.compareAndSet(true, false)) {
                        log.error("Cannot upsert a batch of {} chunks, further failures are logged without stack trace",
                                points.size(), error);
                    } else {
                        log.warn("Cannot upsert a batch of {} chunks: {}", points.size(), error.toString());
                    }
                })
                .then();
    }

    @Override
    public Mono<InformationChunk> getChunk(final String chunkId) {
        final Points.PointId pointId = QdrantConverter.getChunkIdToPointIdConverter().convert().apply(chunkId);

        return toMono(() -> qdrantClient.retrieveAsync(COLLECTION_NAME, pointId, true, false, null))
                .flatMapIterable(points -> points)
                .next()
                .map(point -> QdrantConverter.getPayloadToInformationChunkConverter().convert().apply(point.getPayloadMap()));
    }

    @Override
    public Mono<Void> deleteOldIngestionChunks(final String projectName, final String ingestionId) {
        final Points.Filter otherIngestions = Points.Filter.newBuilder()
                .addMust(matchKeyword(PROJECT_NAME_FIELD, projectName))
                .addMustNot(matchKeyword(INGESTION_ID_FIELD, ingestionId))
                .build();

        return toMono(() -> qdrantClient.deleteAsync(COLLECTION_NAME, otherIngestions))
                .doOnSuccess(ok -> log.info("Chunks of previous ingestion of project {} deleted", projectName))
                .doOnError(error -> log.warn("Cannot delete the chunks of previous ingestion of project {}: {}",
                        projectName, error.toString()))
                .then();
    }

    @Override
    public Mono<Void> createCollectionIfMissing(final int vectorSize) {
        return toMono(() -> qdrantClient.collectionExistsAsync(COLLECTION_NAME))
                .flatMap(exists -> Boolean.TRUE.equals(exists) ? checkCollection(vectorSize) : createCollection(vectorSize));
    }

    private Mono<Void> checkCollection(final int vectorSize) {
        return toMono(() -> qdrantClient.getCollectionInfoAsync(COLLECTION_NAME))
                .map(collection -> collection.getConfig().getParams())
                .flatMap(collectionParams -> Mono.justOrEmpty(getIncompatibility(collectionParams, vectorSize)))
                .flatMap(incompatibility -> Mono.error(new IllegalStateException(
                        "Collection " + COLLECTION_NAME + " " + incompatibility + ": delete the collection and ingest again")));
    }

    private Optional<String> getIncompatibility(final Collections.CollectionParams collectionParams, final int vectorSize) {
        final Collections.VectorParamsMap vectors = collectionParams.getVectorsConfig().getParamsMap();

        if (!vectors.containsMap(DENSE_VECTOR) || !collectionParams.getSparseVectorsConfig().containsMap(KEYWORD_VECTOR)) {
            return Optional.of("was created without keyword vectors");
        }

        final long existingVectorSize = vectors.getMapOrThrow(DENSE_VECTOR).getSize();

        return existingVectorSize == vectorSize ?
                Optional.empty() :
                Optional.of("stores vectors of size " + existingVectorSize
                        + " but the embedding model produces vectors of size " + vectorSize);
    }

    private Mono<Void> createCollection(final int vectorSize) {
        final Collections.CreateCollection collection = Collections.CreateCollection.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setVectorsConfig(Collections.VectorsConfig.newBuilder()
                        .setParamsMap(Collections.VectorParamsMap.newBuilder()
                                .putMap(DENSE_VECTOR, Collections.VectorParams.newBuilder()
                                        .setSize(vectorSize)
                                        .setDistance(Collections.Distance.Cosine)
                                        .build())))
                .setSparseVectorsConfig(Collections.SparseVectorConfig.newBuilder()
                        .putMap(KEYWORD_VECTOR, Collections.SparseVectorParams.newBuilder()
                                .setModifier(Collections.Modifier.Idf)
                                .build()))
                .setOptimizersConfig(Collections.OptimizersConfigDiff.newBuilder()
                        .setIndexingThreshold(INDEXING_DISABLED))
                .build();

        return toMono(() -> qdrantClient.createCollectionAsync(collection))
                .doOnSuccess(ok -> log.info("Qdrant collection {} created with vectors of size {}", COLLECTION_NAME, vectorSize))
                .then();
    }

    @Override
    public Mono<Boolean> deleteCollection(final String collectionName) {
        return toMono(() -> qdrantClient.collectionExistsAsync(collectionName))
                .filter(Boolean::booleanValue)
                .flatMap(exists -> toMono(() -> qdrantClient.deleteCollectionAsync(collectionName)))
                .map(Collections.CollectionOperationResponse::getResult)
                .defaultIfEmpty(false)
                .doOnNext(deleted -> {
                    if (Boolean.TRUE.equals(deleted)) {
                        log.info("Qdrant collection {} deleted", collectionName);
                    }
                });
    }

    @Override
    public Mono<List<RetrievedChunk>> searchSimilarChunks(final String query, final List<Float> queryVector,
                                                          final String projectName) {
        final double minScore = qdrantClientProperties.getQdrantSearchMinScore();
        final KeywordVector queryKeywords = KeywordVectorizer.ofQuery(query);
        final Points.QueryPoints.Builder search = Points.QueryPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setLimit(qdrantClientProperties.getQdrantSearchTopK())
                .setWithPayload(WithPayloadSelectorFactory.enable(true));

        if (Boolean.TRUE.equals(qdrantClientProperties.getQdrantSearchHybridEnabled()) && !queryKeywords.isEmpty()) {
            search.addPrefetch(prefetch(nearest(queryVector), DENSE_VECTOR, projectName, minScore))
                    .addPrefetch(prefetch(nearest(queryKeywords.getValues(), queryKeywords.getIndices()),
                            KEYWORD_VECTOR, projectName, 0))
                    .setQuery(fusion(Points.Fusion.RRF));
        } else {
            search.setQuery(nearest(queryVector)).setUsing(DENSE_VECTOR);
            if (minScore > 0) {
                search.setScoreThreshold((float) minScore);
            }
            if (StringUtils.isNotBlank(projectName)) {
                search.setFilter(matchFilter(PROJECT_NAME_FIELD, projectName));
            }
        }

        return toMono(() -> qdrantClient.queryAsync(search.build()))
                .map(points -> points.stream()
                        .filter(point -> !point.getPayloadMap().isEmpty())
                        .map(QdrantConverter.getScoredPointToRetrievedChunkConverter().convert())
                        .toList());
    }

    private Points.PrefetchQuery prefetch(final Points.Query query, final String vectorName, final String projectName,
                                          final double minScore) {
        final PrefetchRequest prefetchRequest = PrefetchRequest.builder()
                .query(query)
                .vectorName(vectorName)
                .projectName(projectName)
                .minScore(minScore)
                .limit(Math.max(qdrantClientProperties.getQdrantSearchPrefetchLimit(), qdrantClientProperties.getQdrantSearchTopK()))
                .build();

        return QdrantConverter.getPrefetchRequestToPrefetchQueryConverter().convert().apply(prefetchRequest);
    }

    @Override
    public Mono<List<InformationChunk>> findChunksByType(final InformationType informationType, final int limit) {
        final Points.ScrollPoints scroll = Points.ScrollPoints.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setFilter(matchFilter(INFORMATION_TYPE_FIELD, informationType.toString()))
                .setLimit(limit)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .build();

        return toMono(() -> qdrantClient.scrollAsync(scroll))
                .map(response -> response.getResultList().stream()
                        .filter(point -> !point.getPayloadMap().isEmpty())
                        .map(point -> QdrantConverter.getPayloadToInformationChunkConverter().convert().apply(point.getPayloadMap()))
                        .toList());
    }

    @Override
    public Mono<Map<String, Long>> countChunksByProject() {
        final Points.FacetCounts facet = Points.FacetCounts.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setKey(PROJECT_NAME_FIELD)
                .setLimit(MAX_PROJECTS)
                .build();

        return toMono(() -> qdrantClient.facetAsync(facet))
                .map(hits -> hits.stream()
                        .collect(Collectors.toMap(
                                hit -> hit.getValue().getStringValue(),
                                Points.FacetHit::getCount,
                                Long::sum,
                                TreeMap::new)));
    }

    @Override
    public Mono<Void> disableIndexing() {
        return updateIndexingThreshold(INDEXING_DISABLED)
                .doOnError(error -> log.warn("Cannot disable the Qdrant indexing: {}", error.getMessage()));
    }

    @Override
    public Mono<Void> enableIndexing() {
        return updateIndexingThreshold(qdrantClientProperties.getQdrantIndexingThreshold())
                .then(createPayloadIndexes())
                .doOnError(error -> log.error("Cannot enable the Qdrant indexing", error));
    }

    private Mono<Void> updateIndexingThreshold(final int indexingThreshold) {
        final Collections.UpdateCollection update = Collections.UpdateCollection.newBuilder()
                .setCollectionName(COLLECTION_NAME)
                .setOptimizersConfig(Collections.OptimizersConfigDiff.newBuilder()
                        .setIndexingThreshold(indexingThreshold))
                .build();

        return toMono(() -> qdrantClient.updateCollectionAsync(update))
                .doOnSuccess(ok -> log.info("Qdrant indexing threshold set to {}", indexingThreshold))
                .then();
    }

    private Mono<Void> createPayloadIndexes() {
        return Flux.just(PROJECT_NAME_FIELD, INFORMATION_TYPE_FIELD)
                .concatMap(field -> toMono(() -> qdrantClient.createPayloadIndexAsync(
                        COLLECTION_NAME, field, Collections.PayloadSchemaType.Keyword, null, true, null, null))
                        .doOnSuccess(ok -> log.info("Qdrant payload index ready for field: {}", field)))
                .then();
    }

    private Points.Filter matchFilter(final String field, final String value) {
        return Points.Filter.newBuilder()
                .addMust(matchKeyword(field, value))
                .build();
    }
}
