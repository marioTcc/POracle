package com.mtcc.oracle.config;

import com.langfuse.client.AsyncLangfuseClient;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@RequiredArgsConstructor
public class LangfuseClientConfig {

    private static final String SERVICE_NAME = "poracle";
    private static final String TRACES_PATH = "/api/public/otel/v1/traces";
    private static final String INGESTION_VERSION = "4";

    private final LangfuseClientProperties langfuseClientProperties;

    @Bean
    public AsyncLangfuseClient langfuseClient() {
        return AsyncLangfuseClient.builder()
                .url(langfuseClientProperties.getLangfuseHost())
                .credentials(langfuseClientProperties.getLangfusePublicKey(), langfuseClientProperties.getLangfuseSecretKey())
                .build();
    }

    @Bean
    public OpenTelemetry langfuseOpenTelemetry() {
        if (!Boolean.TRUE.equals(langfuseClientProperties.getIsTracingEnabled())) {
            return OpenTelemetry.noop();
        }

        final OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(langfuseClientProperties.getLangfuseHost() + TRACES_PATH)
                .addHeader("Authorization", "Basic " + langfuseClientProperties.getAuthHeader())
                .addHeader("x-langfuse-ingestion-version", INGESTION_VERSION)
                .build();

        return OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .setResource(Resource.getDefault().merge(
                                Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), SERVICE_NAME))))
                        .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                        .build())
                .build();
    }

    @Bean
    public Tracer langfuseTracer(final OpenTelemetry langfuseOpenTelemetry) {
        return langfuseOpenTelemetry.getTracer(SERVICE_NAME);
    }
}
