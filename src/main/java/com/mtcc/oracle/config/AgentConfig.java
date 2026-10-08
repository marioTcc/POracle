package com.mtcc.oracle.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Configuration
@RequiredArgsConstructor
public class AgentConfig {

    private final AgentProperties agentProperties;
    private final ChatModelListener chatModelTracingListener;

    @Bean
    public ChatModel generalPurposeModel() {
        return OpenAiChatModel.builder()
                .baseUrl(agentProperties.getOllamaBaseUrl().concat("/v1"))
                .modelName(agentProperties.getOllamaGeneralPurposeModelName())
                .timeout(Duration.ofMinutes(5))
                .apiKey("ollama")
                .maxTokens(2000)
                .temperature(0.2)
                .logRequests(agentProperties.getIsRequestLoggingEnabled())
                .logResponses(agentProperties.getIsResponseLoggingEnabled())
                .listeners(chatModelTracingListener)
                .build();
    }

    @Bean
    public ChatModel codingModel() {
        return OpenAiChatModel.builder()
                .baseUrl(agentProperties.getOllamaBaseUrl().concat("/v1"))
                .modelName(agentProperties.getOllamaCodingModelName())
                .timeout(Duration.ofMinutes(5))
                .apiKey("ollama")
                .maxTokens(2000)
                .temperature(0.0)
                .logRequests(agentProperties.getIsRequestLoggingEnabled())
                .logResponses(agentProperties.getIsResponseLoggingEnabled())
                .listeners(chatModelTracingListener)
                .build();
    }

    @Bean
    public ChatModel judgeModel() {
        return OpenAiChatModel.builder()
                .baseUrl(agentProperties.getOllamaBaseUrl().concat("/v1"))
                .modelName(agentProperties.getOllamaJudgeModelName())
                .timeout(Duration.ofMinutes(5))
                .apiKey("ollama")
                .maxTokens(1000)
                .temperature(0.0)
                .logRequests(agentProperties.getIsRequestLoggingEnabled())
                .logResponses(agentProperties.getIsResponseLoggingEnabled())
                .listeners(chatModelTracingListener)
                .build();
    }
}
