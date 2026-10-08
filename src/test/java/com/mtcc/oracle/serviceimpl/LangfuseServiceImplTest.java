package com.mtcc.oracle.serviceimpl;

import com.langfuse.client.AsyncLangfuseClient;
import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.observability.ExperimentTrace;
import com.mtcc.oracle.observability.GenerationTrace;
import com.mtcc.oracle.observability.TraceParentChatModel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LangfuseServiceImplTest {

    private static final String DATASET_ITEM = """
            {"id": "item-1", "status": "ACTIVE", "input": "What is Spring AI?", "expectedOutput": "A framework.",
             "metadata": {"category": "basics"}, "datasetId": "dataset-1", "datasetName": "golden",
             "createdAt": "2026-10-05T10:00:00.000Z", "updatedAt": "2026-10-05T10:00:00.000Z"}""";

    private static final String RUN_ITEM = """
            {"id": "run-item-1", "datasetRunId": "run-1", "datasetRunName": "golden-run-1", "datasetItemId": "item-1",
             "traceId": "trace-1", "createdAt": "2026-10-05T10:00:00.000Z", "updatedAt": "2026-10-05T10:00:00.000Z"}""";

    private final EvaluationItem item = EvaluationItem.builder()
            .id("item-1").datasetId("dataset-1").input("What is Spring AI?").expectedOutput("A framework.").build();

    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private final List<SpanData> exportedSpans = new CopyOnWriteArrayList<>();

    private final SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(new SpanExporter() {
                @Override
                public CompletableResultCode export(final Collection<SpanData> spans) {
                    exportedSpans.addAll(spans);
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode flush() {
                    return CompletableResultCode.ofSuccess();
                }

                @Override
                public CompletableResultCode shutdown() {
                    return CompletableResultCode.ofSuccess();
                }
            }))
            .build();

    private LangfuseServiceImpl langfuseService(final int status, final String responseBody) {
        return langfuseService(status, query -> responseBody);
    }

    private LangfuseServiceImpl langfuseService(final int status, final UnaryOperator<String> responseBodyOfQuery) {
        final OkHttpClient httpClient = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    final Buffer body = new Buffer();
                    if (chain.request().body() != null) {
                        chain.request().body().writeTo(body);
                    }
                    requests.add(chain.request().method() + " " + chain.request().url().encodedPath()
                            + (chain.request().url().query() == null ? "" : "?" + chain.request().url().query()));
                    bodies.add(body.readUtf8());
                    return new Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(status)
                            .message("")
                            .body(ResponseBody.create(responseBodyOfQuery.apply(String.valueOf(chain.request().url().query())),
                                    MediaType.get("application/json")))
                            .build();
                })
                .build();

        return new LangfuseServiceImpl(AsyncLangfuseClient.builder()
                .url("http://localhost:3000")
                .credentials("public-key", "secret-key")
                .httpClient(httpClient)
                .build(), tracerProvider.get("test"));
    }

    @Test
    void upsertDataset_ShouldCreateTheDatasetWithItsDescription() {
        langfuseService(200, """
                {"id": "dataset-1", "name": "golden", "description": "Golden dataset", "projectId": "project-1",
                 "createdAt": "2026-10-05T10:00:00.000Z", "updatedAt": "2026-10-05T10:00:00.000Z"}""")
                .upsertDataset("golden", "Golden dataset").block();

        assertEquals(List.of("POST /api/public/v2/datasets"), requests);
        assertTrue(bodies.get(0).contains("\"name\":\"golden\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"description\":\"Golden dataset\""), bodies.get(0));
    }

    @Test
    void postDatasetItem_ShouldSendTheQuestionTheAnswerAndTheMetadata() {
        langfuseService(200, DATASET_ITEM)
                .postDatasetItem("golden", "What is Spring AI?", "A framework.", Map.of("category", "basics")).block();

        assertEquals(List.of("POST /api/public/dataset-items"), requests);
        assertTrue(bodies.get(0).contains("\"datasetName\":\"golden\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"input\":\"What is Spring AI?\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"expectedOutput\":\"A framework.\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"metadata\":{\"category\":\"basics\"}"), bodies.get(0));
    }

    @Test
    void getDatasetItems_ShouldMapTheItemsOfTheGivenDataset() {
        final List<EvaluationItem> items = langfuseService(200,
                "{\"data\": [" + DATASET_ITEM + "], \"meta\": {\"page\": 1, \"limit\": 50, \"totalItems\": 1, \"totalPages\": 1}}")
                .getDatasetItems("golden").block();

        assertEquals(1, items.size());
        assertEquals("item-1", items.get(0).getId());
        assertEquals("What is Spring AI?", items.get(0).getInput());
        assertEquals("A framework.", items.get(0).getExpectedOutput());
        assertEquals("dataset-1", items.get(0).getDatasetId());
        assertEquals("basics", items.get(0).getCategory());
        assertEquals(List.of("GET /api/public/dataset-items?datasetName=golden&page=1"), requests);
    }

    @Test
    void getDatasetItems_ShouldReadEveryPageOfTheDataset() {
        final String secondItem = DATASET_ITEM.replace("item-1", "item-2");

        final List<EvaluationItem> items = langfuseService(200, query -> query.endsWith("page=1") ?
                "{\"data\": [" + DATASET_ITEM + "], \"meta\": {\"page\": 1, \"limit\": 1, \"totalItems\": 2, \"totalPages\": 2}}" :
                "{\"data\": [" + secondItem + "], \"meta\": {\"page\": 2, \"limit\": 1, \"totalItems\": 2, \"totalPages\": 2}}")
                .getDatasetItems("golden").block();

        assertEquals(List.of("item-1", "item-2"), items.stream().map(EvaluationItem::getId).toList());
        assertEquals(List.of("GET /api/public/dataset-items?datasetName=golden&page=1",
                "GET /api/public/dataset-items?datasetName=golden&page=2"), requests);
    }

    @Test
    void postScore_ShouldSendANumericScoreForTheRootObservationOfTheExperiment() {
        final LangfuseServiceImpl langfuseService = langfuseService(200, RUN_ITEM);
        final ExperimentTrace experiment = langfuseService.startExperiment("golden-run-1", item).block();

        langfuseService.postScore(experiment, "answer-recall", 0.8, "judged").block();

        assertEquals("POST /api/public/scores", requests.get(1));
        assertTrue(bodies.get(1).contains("\"traceId\":\"" + experiment.getTraceId() + "\""), bodies.get(1));
        assertTrue(bodies.get(1).contains("\"observationId\":\"" + experiment.getObservationId() + "\""), bodies.get(1));
        assertTrue(bodies.get(1).contains("\"name\":\"answer-recall\""), bodies.get(1));
        assertTrue(bodies.get(1).contains("\"value\":0.8"), bodies.get(1));
        assertTrue(bodies.get(1).contains("\"dataType\":\"NUMERIC\""), bodies.get(1));
    }

    @Test
    void postScore_ShouldFail_WhenTheRequestIsRejected() {
        final LangfuseServiceImpl langfuseService = langfuseService(401, "{\"message\": \"Invalid credentials\"}");
        final ExperimentTrace experiment = langfuseService.startExperiment("golden-run-1", item).block();

        assertThrows(RuntimeException.class, () -> langfuseService.postScore(experiment, "answer-recall", 0.8, "judged").block());
    }

    private SpanData exportedSpan(final String name) {
        return exportedSpans.stream()
                .filter(span -> span.getName().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No exported span named " + name));
    }

    private String attribute(final SpanData span, final String key) {
        return span.getAttributes().get(AttributeKey.stringKey(key));
    }

    @Test
    void startGeneration_ShouldExportASpanWithTheModelInputOutputAndTokens() {
        final GenerationTrace generation = langfuseService(200, "{}").startGeneration("llama3.1:8b", "What is Spring AI?");
        generation.complete("A framework.", 12, 3);

        final SpanData span = exportedSpan("chat-model-generation");
        assertEquals("llama3.1:8b", attribute(span, "gen_ai.request.model"));
        assertEquals("What is Spring AI?", attribute(span, "langfuse.observation.input"));
        assertEquals("A framework.", attribute(span, "langfuse.observation.output"));
        assertEquals(12L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.input_tokens")));
        assertEquals(3L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.output_tokens")));
    }

    @Test
    void startGeneration_ShouldExportTheFailureOfTheModel() {
        langfuseService(200, "{}").startGeneration("llama3.1:8b", "What is Spring AI?")
                .fail(new IllegalStateException("model not available"));

        assertEquals("exception", exportedSpan("chat-model-generation").getEvents().get(0).getName());
    }

    @Test
    void startExperiment_ShouldLinkTheItemToTheRunAndExportARootSpanThatModelCallsCanJoin() {
        final LangfuseServiceImpl langfuseService = langfuseService(200, RUN_ITEM);

        final ExperimentTrace experiment = langfuseService.startExperiment("golden-run-1", item).block();
        final Context traceParent = experiment.addTo(reactor.util.context.Context.empty()).get(TraceParentChatModel.TRACE_PARENT_KEY);
        try (Scope ignored = traceParent.makeCurrent()) {
            langfuseService.startGeneration("llama3.1:8b", "What is Spring AI?").complete("A framework.", null, null);
        }
        experiment.setOutput("A framework.");
        experiment.end();

        final SpanData experimentSpan = exportedSpan("experiment-task");
        final SpanData generationSpan = exportedSpan("chat-model-generation");
        assertEquals(experiment.getTraceId(), experimentSpan.getTraceId());
        assertFalse(experimentSpan.getParentSpanContext().isValid(), "The experiment must be the root of its trace");
        assertEquals(experiment.getObservationId(), experimentSpan.getSpanId());
        assertEquals("What is Spring AI?", attribute(experimentSpan, "langfuse.observation.input"));
        assertEquals("A framework.", attribute(experimentSpan, "langfuse.experiment.item.expected_output"));
        assertEquals("A framework.", attribute(experimentSpan, "langfuse.observation.output"));
        assertEquals(experimentSpan.getTraceId(), generationSpan.getTraceId());
        assertEquals(experimentSpan.getSpanId(), generationSpan.getParentSpanId());
        List.of(experimentSpan, generationSpan).forEach(span -> {
            assertEquals("run-1", attribute(span, "langfuse.experiment.id"));
            assertEquals("golden-run-1", attribute(span, "langfuse.experiment.name"));
            assertEquals("dataset-1", attribute(span, "langfuse.experiment.dataset.id"));
            assertEquals("item-1", attribute(span, "langfuse.experiment.item.id"));
            assertEquals(experimentSpan.getSpanId(), attribute(span, "langfuse.experiment.item.root_observation_id"));
        });
        assertEquals(List.of("POST /api/public/dataset-run-items"), requests);
        assertTrue(bodies.get(0).contains("\"runName\":\"golden-run-1\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"datasetItemId\":\"item-1\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"traceId\":\"" + experimentSpan.getTraceId() + "\""), bodies.get(0));
        assertTrue(bodies.get(0).contains("\"observationId\":\"" + experimentSpan.getSpanId() + "\""), bodies.get(0));
    }

    @Test
    void startExperiment_ShouldStillStart_WhenTheItemCannotBeLinkedToTheRun() {
        final ExperimentTrace experiment = langfuseService(500, "{\"message\": \"error\"}")
                .startExperiment("golden-run-1", item).block();
        experiment.end();

        assertEquals("golden-run-1", attribute(exportedSpan("experiment-task"), "langfuse.experiment.id"));
    }

    @Test
    void startGeneration_ShouldNotCarryExperimentAttributes_OutsideAnExperiment() {
        langfuseService(200, "{}").startGeneration("llama3.1:8b", "What is Spring AI?").complete("A framework.", null, null);

        assertNull(attribute(exportedSpan("chat-model-generation"), "langfuse.experiment.id"));
    }
}
