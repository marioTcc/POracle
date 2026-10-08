package com.mtcc.oracle.config;

import com.mtcc.oracle.observability.GenerationTrace;
import com.mtcc.oracle.serviceimpl.interfaces.IObservabilityServiceImpl;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChatModelTracingConfigTest {

    private static final String MODEL_NAME = "llama3.1:8b";

    @Mock
    private IObservabilityServiceImpl observabilityService;
    @Mock
    private GenerationTrace generationTrace;

    private ChatModelListener listener;

    @BeforeEach
    void setUp() {
        listener = new ChatModelTracingConfig().chatModelTracingListener(observabilityService);
        when(observabilityService.startGeneration(any(), anyString())).thenReturn(generationTrace);
    }

    private ChatModel chatModel(final ChatResponse response) {
        return new ChatModel() {
            @Override
            public ChatResponse doChat(final ChatRequest chatRequest) {
                if (response == null) {
                    throw new IllegalStateException("model not available");
                }
                return response;
            }

            @Override
            public ChatRequestParameters defaultRequestParameters() {
                return ChatRequestParameters.builder().modelName(MODEL_NAME).build();
            }

            @Override
            public List<ChatModelListener> listeners() {
                return List.of(listener);
            }
        };
    }

    private ChatRequest question() {
        return ChatRequest.builder().messages(UserMessage.from("What is spring-ai?")).build();
    }

    @Test
    void listener_ShouldTraceTheModelInputOutputAndTokens() {
        chatModel(ChatResponse.builder()
                .aiMessage(AiMessage.from("A framework."))
                .tokenUsage(new TokenUsage(12, 3))
                .build()).chat(question());

        verify(observabilityService).startGeneration(eq(MODEL_NAME), Mockito.contains("What is spring-ai?"));
        verify(generationTrace).complete("A framework.", 12, 3);
        verify(generationTrace, never()).fail(any());
    }

    @Test
    void listener_ShouldTraceTheAnswerWithoutTokens_WhenTheModelDoesNotReportThem() {
        chatModel(ChatResponse.builder().aiMessage(AiMessage.from("A framework.")).build()).chat(question());

        verify(generationTrace).complete("A framework.", null, null);
    }

    @Test
    void listener_ShouldTraceTheFailureOfTheModel() {
        final ChatModel failingModel = chatModel(null);
        final ChatRequest question = question();

        final IllegalStateException error = assertThrows(IllegalStateException.class, () -> failingModel.chat(question));

        assertEquals("model not available", error.getMessage());
        verify(generationTrace).fail(error);
        verify(generationTrace, never()).complete(any(), any(), any());
    }
}
