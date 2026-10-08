package com.mtcc.oracle.observability;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import lombok.RequiredArgsConstructor;
import reactor.util.context.ContextView;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class TraceParentChatModel implements ChatModel {

    public static final Class<Context> TRACE_PARENT_KEY = Context.class;

    private final ChatModel chatModel;
    private final Context traceParent;

    public static ChatModel of(final ChatModel chatModel, final ContextView subscriberContext) {
        return subscriberContext.<Context>getOrEmpty(TRACE_PARENT_KEY)
                .<ChatModel>map(traceParent -> new TraceParentChatModel(chatModel, traceParent))
                .orElse(chatModel);
    }

    @Override
    public ChatResponse chat(final ChatRequest chatRequest, final ChatRequestOptions options) {
        try (Scope ignored = traceParent.makeCurrent()) {
            return chatModel.chat(chatRequest, options);
        }
    }

    @Override
    public CompletableFuture<ChatResponse> chatAsync(final ChatRequest chatRequest, final ChatRequestOptions options) {
        try (Scope ignored = traceParent.makeCurrent()) {
            return chatModel.chatAsync(chatRequest, options);
        }
    }

    @Override
    public ChatResponse doChat(final ChatRequest chatRequest) {
        return chatModel.doChat(chatRequest);
    }

    @Override
    public CompletableFuture<ChatResponse> doChatAsync(final ChatRequest chatRequest) {
        return chatModel.doChatAsync(chatRequest);
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return chatModel.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return chatModel.listeners();
    }

    @Override
    public ModelProvider provider() {
        return chatModel.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return chatModel.supportedCapabilities();
    }
}
