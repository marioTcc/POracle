package com.mtcc.rag.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class OllamaClientConfig {

    private final OllamaClientProperties ollamaClientProperties;

    @Bean
    public WebClient ollamaClient(final WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(ollamaClientProperties.getOllamaBaseUrl())
                .build();
    }
}
