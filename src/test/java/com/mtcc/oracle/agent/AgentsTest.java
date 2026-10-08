package com.mtcc.oracle.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.AiServices;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentsTest {

    private static final String MODEL_ANSWER = "model answer";

    private final ChatModel chatModel = new ChatModel() {
        @Override
        public CompletableFuture<ChatResponse> doChatAsync(final ChatRequest chatRequest) {
            return CompletableFuture.completedFuture(
                    ChatResponse.builder().aiMessage(AiMessage.from(MODEL_ANSWER)).build());
        }
    };

    @Test
    void codingAgent_ShouldReturnTheModelAnswerReactively() {
        final CodingAgent codingAgent = AiServices.builder(CodingAgent.class).chatModel(chatModel).build();

        assertEquals(MODEL_ANSWER, codingAgent.analyzeProjectStructure("src/\n main/").block());
    }

    @Test
    void docsAnalyzerAgent_ShouldReturnTheModelAnswerReactively() {
        final DocsAnalyzerAgent docsAnalyzerAgent = AiServices.builder(DocsAnalyzerAgent.class).chatModel(chatModel).build();

        assertEquals(MODEL_ANSWER, docsAnalyzerAgent.summarizeProject("spring-ai", "An AI framework.").block());
        assertEquals(MODEL_ANSWER, docsAnalyzerAgent.summarizeModule("spring-ai", "models/openai", "OpenAI module.").block());
        assertEquals(MODEL_ANSWER, docsAnalyzerAgent.answerQuestion("What is spring-ai?").block());
    }
}
