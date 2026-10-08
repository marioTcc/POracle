package com.mtcc.oracle.config;

import com.mtcc.oracle.observability.GenerationTrace;
import com.mtcc.oracle.serviceimpl.interfaces.IObservabilityServiceImpl;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.Optional;

@Configuration
public class ChatModelTracingConfig {

    private static final String GENERATION_TRACE_KEY = "generation_trace";

    @Bean
    public ChatModelListener chatModelTracingListener(final IObservabilityServiceImpl observabilityService) {
        return new ChatModelListener() {
            @Override
            public void onRequest(final ChatModelRequestContext context) {
                context.attributes().put(GENERATION_TRACE_KEY, observabilityService.startGeneration(
                        context.chatRequest().modelName(), context.chatRequest().messages().toString()));
            }

            @Override
            public void onResponse(final ChatModelResponseContext context) {
                final Optional<TokenUsage> tokenUsage = Optional.ofNullable(context.chatResponse().tokenUsage());

                getGenerationTrace(context.attributes()).ifPresent(generationTrace -> generationTrace.complete(
                        context.chatResponse().aiMessage().text(),
                        tokenUsage.map(TokenUsage::inputTokenCount).orElse(null),
                        tokenUsage.map(TokenUsage::outputTokenCount).orElse(null)));
            }

            @Override
            public void onError(final ChatModelErrorContext context) {
                getGenerationTrace(context.attributes()).ifPresent(generationTrace -> generationTrace.fail(context.error()));
            }
        };
    }

    private Optional<GenerationTrace> getGenerationTrace(final Map<Object, Object> attributes) {
        return Optional.ofNullable(attributes.get(GENERATION_TRACE_KEY))
                .filter(GenerationTrace.class::isInstance)
                .map(GenerationTrace.class::cast);
    }
}
