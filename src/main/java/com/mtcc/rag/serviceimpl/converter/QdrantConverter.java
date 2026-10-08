package com.mtcc.rag.serviceimpl.converter;

import com.mtcc.common.converter.Converter;
import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.rag.util.KeywordVector;
import com.mtcc.rag.util.KeywordVectorizer;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import io.vavr.control.Try;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;
import reactor.util.function.Tuple2;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static io.qdrant.client.ConditionFactory.matchKeyword;

public class QdrantConverter {

    public static final String PROJECT_NAME_FIELD = "projectName";
    public static final String INFORMATION_TYPE_FIELD = "informationType";
    public static final String INGESTION_ID_FIELD = "ingestionId";
    public static final String DENSE_VECTOR = "dense";
    public static final String KEYWORD_VECTOR = "keywords";

    private QdrantConverter() {}

    @Getter
    private static final InformationChunkToPayloadConverter informationChunkToPayloadConverter = new InformationChunkToPayloadConverter();

    @Getter
    private static final PayloadToInformationChunkConverter payloadToInformationChunkConverter = new PayloadToInformationChunkConverter();

    @Getter
    private static final InformationChunkToVectorsConverter informationChunkToVectorsConverter = new InformationChunkToVectorsConverter();

    @Getter
    private static final ChunkIdToPointIdConverter chunkIdToPointIdConverter = new ChunkIdToPointIdConverter();

    @Getter
    private static final InformationChunkToPointConverter informationChunkToPointConverter = new InformationChunkToPointConverter();

    @Getter
    private static final ScoredPointToRetrievedChunkConverter scoredPointToRetrievedChunkConverter = new ScoredPointToRetrievedChunkConverter();

    @Getter
    private static final PrefetchRequestToPrefetchQueryConverter prefetchRequestToPrefetchQueryConverter = new PrefetchRequestToPrefetchQueryConverter();

    public static class InformationChunkToPayloadConverter implements Converter<InformationChunk, Map<String, JsonWithInt.Value>> {

        @Override
        public Function<InformationChunk, Map<String, JsonWithInt.Value>> convert() {
            return this::toPayload;
        }

        private Map<String, JsonWithInt.Value> toPayload(final InformationChunk chunk) {
            final Map<String, String> fields = new LinkedHashMap<>();
            fields.put(PROJECT_NAME_FIELD, chunk.getProjectName());
            fields.put("chunkId", chunk.getChunkId());
            fields.put("filename", chunk.getFilename());
            fields.put("filePath", chunk.getFilePath());
            fields.put("language", chunk.getLanguage());
            fields.put(INFORMATION_TYPE_FIELD, chunk.getInformationType().toString());
            fields.put("text", chunk.getText());
            fields.put("chunkingStrategy", chunk.getChunkingStrategy());
            fields.put("context", chunk.getContext());
            fields.put(INGESTION_ID_FIELD, Objects.toString(chunk.getIngestionId(), ""));

            return fields.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, field -> ValueFactory.value(String.valueOf(field.getValue()))));
        }
    }

    public static class PayloadToInformationChunkConverter implements Converter<Map<String, JsonWithInt.Value>, InformationChunk> {

        @Override
        public Function<Map<String, JsonWithInt.Value>, InformationChunk> convert() {
            return this::toChunk;
        }

        private InformationChunk toChunk(final Map<String, JsonWithInt.Value> payload) {
            return InformationChunk.builder()
                    .projectName(getText(payload, PROJECT_NAME_FIELD))
                    .chunkId(getText(payload, "chunkId"))
                    .filename(getText(payload, "filename"))
                    .filePath(getText(payload, "filePath"))
                    .language(getText(payload, "language"))
                    .informationType(Try.of(() -> InformationType.valueOf(getText(payload, INFORMATION_TYPE_FIELD)))
                            .getOrNull())
                    .text(getText(payload, "text"))
                    .chunkingStrategy(getText(payload, "chunkingStrategy"))
                    .context(getText(payload, "context"))
                    .ingestionId(getText(payload, INGESTION_ID_FIELD))
                    .build();
        }

        private String getText(final Map<String, JsonWithInt.Value> payload, final String field) {
            return payload.containsKey(field) ? payload.get(field).getStringValue() : "";
        }
    }

    public static class InformationChunkToVectorsConverter implements Converter<Tuple2<InformationChunk, List<Float>>, Map<String, Points.Vector>> {

        @Override
        public Function<Tuple2<InformationChunk, List<Float>>, Map<String, Points.Vector>> convert() {
            return this::toVectors;
        }

        private Map<String, Points.Vector> toVectors(final Tuple2<InformationChunk, List<Float>> chunkAndEmbedding) {
            final KeywordVector keywords = KeywordVectorizer.ofPassage(chunkAndEmbedding.getT1().toEmbeddingText());
            final Map<String, Points.Vector> vectors = new LinkedHashMap<>();

            vectors.put(DENSE_VECTOR, VectorFactory.vector(chunkAndEmbedding.getT2()));
            if (!keywords.isEmpty()) {
                vectors.put(KEYWORD_VECTOR, VectorFactory.vector(keywords.getValues(), keywords.getIndices()));
            }
            return vectors;
        }
    }

    public static class ChunkIdToPointIdConverter implements Converter<String, Points.PointId> {

        @Override
        public Function<String, Points.PointId> convert() {
            return chunkId -> PointIdFactory.id(UUID.fromString(chunkId));
        }
    }

    public static class InformationChunkToPointConverter implements Converter<Tuple2<InformationChunk, List<Float>>, Points.PointStruct> {

        @Override
        public Function<Tuple2<InformationChunk, List<Float>>, Points.PointStruct> convert() {
            return this::toPoint;
        }

        private Points.PointStruct toPoint(final Tuple2<InformationChunk, List<Float>> chunkAndEmbedding) {
            final InformationChunk chunk = chunkAndEmbedding.getT1();

            return Points.PointStruct.newBuilder()
                    .setId(chunkIdToPointIdConverter.convert().apply(chunk.getChunkId()))
                    .setVectors(VectorsFactory.namedVectors(informationChunkToVectorsConverter.convert().apply(chunkAndEmbedding)))
                    .putAllPayload(informationChunkToPayloadConverter.convert().apply(chunk))
                    .build();
        }
    }

    public static class ScoredPointToRetrievedChunkConverter implements Converter<Points.ScoredPoint, RetrievedChunk> {

        @Override
        public Function<Points.ScoredPoint, RetrievedChunk> convert() {
            return point -> RetrievedChunk.builder()
                    .chunk(payloadToInformationChunkConverter.convert().apply(point.getPayloadMap()))
                    .score(point.getScore())
                    .build();
        }
    }

    public static class PrefetchRequestToPrefetchQueryConverter implements Converter<PrefetchRequest, Points.PrefetchQuery> {

        @Override
        public Function<PrefetchRequest, Points.PrefetchQuery> convert() {
            return this::toPrefetchQuery;
        }

        private Points.PrefetchQuery toPrefetchQuery(final PrefetchRequest prefetchRequest) {
            final Points.PrefetchQuery.Builder prefetch = Points.PrefetchQuery.newBuilder()
                    .setQuery(prefetchRequest.getQuery())
                    .setUsing(prefetchRequest.getVectorName())
                    .setLimit(prefetchRequest.getLimit());

            if (prefetchRequest.getMinScore() > 0) {
                prefetch.setScoreThreshold((float) prefetchRequest.getMinScore());
            }
            if (StringUtils.isNotBlank(prefetchRequest.getProjectName())) {
                prefetch.setFilter(Points.Filter.newBuilder()
                        .addMust(matchKeyword(PROJECT_NAME_FIELD, prefetchRequest.getProjectName())));
            }

            return prefetch.build();
        }
    }
}
