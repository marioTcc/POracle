package com.mtcc.oracle.serviceimpl;

import com.langfuse.client.AsyncLangfuseClient;
import com.langfuse.client.resources.commons.types.CreateScoreValue;
import com.langfuse.client.resources.commons.types.DatasetRunItem;
import com.langfuse.client.resources.commons.types.ScoreDataType;
import com.langfuse.client.resources.datasetitems.requests.GetDatasetItemsRequest;
import com.langfuse.client.resources.datasetitems.types.CreateDatasetItemRequest;
import com.langfuse.client.resources.datasetitems.types.PaginatedDatasetItems;
import com.langfuse.client.resources.datasetrunitems.types.CreateDatasetRunItemRequest;
import com.langfuse.client.resources.datasets.types.CreateDatasetRequest;
import com.langfuse.client.resources.score.types.CreateScoreRequest;
import com.mtcc.oracle.entity.EvaluationItem;
import com.mtcc.oracle.observability.ExperimentTrace;
import com.mtcc.oracle.observability.GenerationTrace;
import com.mtcc.oracle.observability.TraceParentChatModel;
import com.mtcc.oracle.serviceimpl.converter.LangfuseConverter;
import com.mtcc.oracle.serviceimpl.interfaces.IObservabilityServiceImpl;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class LangfuseServiceImpl implements IObservabilityServiceImpl {

    private static final String EXPERIMENT_ATTRIBUTE_PREFIX = "langfuse.experiment.";
    private static final int FIRST_PAGE = 1;

    private final AsyncLangfuseClient langfuseClient;
    private final Tracer langfuseTracer;

    @Override
    public Mono<Void> upsertDataset(final String datasetName, final String datasetDescription) {
        final CreateDatasetRequest request = CreateDatasetRequest.builder()
                .name(datasetName)
                .description(datasetDescription)
                .build();

        return Mono.fromFuture(() -> langfuseClient.datasets().create(request))
                .doOnSuccess(dataset -> log.info("Dataset {} created.", datasetName))
                .doOnError(e -> log.error("Cannot create dataset {}: {}", datasetName, e.getMessage()))
                .then();
    }

    @Override
    public Mono<Void> postDatasetItem(
            final String datasetName, final String question, final String answer, final Map<String, String> metadata) {

        final CreateDatasetItemRequest request = CreateDatasetItemRequest.builder()
                .datasetName(datasetName)
                .input(question)
                .expectedOutput(answer)
                .metadata(metadata)
                .build();

        return Mono.fromFuture(() -> langfuseClient.datasetItems().create(request))
                .doOnSuccess(item -> log.info("Item sent successfully."))
                .then();
    }

    @Override
    public Mono<List<EvaluationItem>> getDatasetItems(final String datasetName) {
        return getDatasetItemsPage(datasetName, FIRST_PAGE)
                .expand(page -> page.getMeta().getPage() < page.getMeta().getTotalPages() ?
                        getDatasetItemsPage(datasetName, page.getMeta().getPage() + 1) : Mono.empty())
                .flatMapIterable(PaginatedDatasetItems::getData)
                .map(LangfuseConverter.getDatasetItemToEvaluationItemConverter().convert())
                .collectList();
    }

    private Mono<PaginatedDatasetItems> getDatasetItemsPage(final String datasetName, final int page) {
        final GetDatasetItemsRequest request = GetDatasetItemsRequest.builder()
                .datasetName(datasetName)
                .page(page)
                .build();

        return Mono.fromFuture(() -> langfuseClient.datasetItems().list(request));
    }

    @Override
    public Mono<Void> postScore(final ExperimentTrace trace, final String scoreName, final double value, final String comment) {
        final String traceId = trace.getTraceId();
        final CreateScoreRequest request = CreateScoreRequest.builder()
                .name(scoreName)
                .value(CreateScoreValue.of(value))
                .traceId(traceId)
                .observationId(trace.getObservationId())
                .comment(comment)
                .dataType(ScoreDataType.NUMERIC)
                .build();

        return Mono.fromFuture(() -> langfuseClient.score().create(request))
                .doOnError(error -> log.error("Cannot send the score {} of trace {}: {}", scoreName, traceId, error.getMessage()))
                .then();
    }

    @Override
    public GenerationTrace startGeneration(final String modelName, final String input) {
        final Span span = langfuseTracer.spanBuilder("chat-model-generation")
                .setParent(Context.current())
                .setAttribute(AttributeKey.stringKey("gen_ai.system"), "Ollama")
                .setAttribute(AttributeKey.stringKey("gen_ai.request.model"), String.valueOf(modelName))
                .setAttribute(AttributeKey.stringKey("langfuse.observation.input"), input)
                .startSpan();

        Baggage.current().forEach((key, entry) -> {
            if (key.startsWith(EXPERIMENT_ATTRIBUTE_PREFIX)) {
                span.setAttribute(AttributeKey.stringKey(key), entry.getValue());
            }
        });

        return new GenerationTrace() {
            @Override
            public void complete(final String output, final Integer inputTokens, final Integer outputTokens) {
                span.setAttribute(AttributeKey.stringKey("langfuse.observation.output"), String.valueOf(output));
                if (inputTokens != null) {
                    span.setAttribute(AttributeKey.longKey("gen_ai.usage.input_tokens"), inputTokens.longValue());
                }
                if (outputTokens != null) {
                    span.setAttribute(AttributeKey.longKey("gen_ai.usage.output_tokens"), outputTokens.longValue());
                }
                span.end();
            }

            @Override
            public void fail(final Throwable error) {
                span.recordException(error);
                span.end();
            }
        };
    }

    @Override
    public Mono<ExperimentTrace> startExperiment(final String runName, final EvaluationItem item) {
        return Mono.defer(() -> {
            final Span span = langfuseTracer.spanBuilder("experiment-task")
                    .setNoParent()
                    .setAttribute(AttributeKey.stringKey("langfuse.observation.input"), String.valueOf(item.getInput()))
                    .setAttribute(AttributeKey.stringKey("langfuse.experiment.item.expected_output"),
                            Objects.toString(item.getExpectedOutput(), ""))
                    .startSpan();
            final SpanContext spanContext = span.getSpanContext();

            return postDatasetRunItem(item.getId(), runName, spanContext)
                    .onErrorReturn(runName)
                    .map(experimentId -> toExperimentTrace(span, Baggage.builder()
                            .put("langfuse.experiment.id", experimentId)
                            .put("langfuse.experiment.name", runName)
                            .put("langfuse.experiment.dataset.id", Objects.toString(item.getDatasetId(), ""))
                            .put("langfuse.experiment.item.id", item.getId())
                            .put("langfuse.experiment.item.root_observation_id", spanContext.getSpanId())
                            .build()));
        });
    }

    private Mono<String> postDatasetRunItem(final String itemId, final String runName, final SpanContext spanContext) {
        final CreateDatasetRunItemRequest request = CreateDatasetRunItemRequest.builder()
                .runName(runName)
                .datasetItemId(itemId)
                .traceId(spanContext.getTraceId())
                .observationId(spanContext.getSpanId())
                .build();

        return Mono.fromFuture(() -> langfuseClient.datasetRunItems().create(request))
                .map(DatasetRunItem::getDatasetRunId)
                .doOnNext(runId -> log.info("Dataset item {} linked to run {} ({})", itemId, runName, runId))
                .doOnError(error -> log.error("Cannot link dataset item {} to run {}: {}", itemId, runName, error.getMessage()));
    }

    private ExperimentTrace toExperimentTrace(final Span span, final Baggage experiment) {
        experiment.forEach((key, entry) -> span.setAttribute(AttributeKey.stringKey(key), entry.getValue()));

        return new ExperimentTrace() {
            @Override
            public String getTraceId() {
                return span.getSpanContext().getTraceId();
            }

            @Override
            public String getObservationId() {
                return span.getSpanContext().getSpanId();
            }

            @Override
            public reactor.util.context.Context addTo(final reactor.util.context.Context subscriberContext) {
                return subscriberContext.put(TraceParentChatModel.TRACE_PARENT_KEY, Context.root().with(span).with(experiment));
            }

            @Override
            public void setOutput(final String output) {
                span.setAttribute(AttributeKey.stringKey("langfuse.observation.output"), String.valueOf(output));
            }

            @Override
            public void end() {
                span.end();
            }
        };
    }

}
