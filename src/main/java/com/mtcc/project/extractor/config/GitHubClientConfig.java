package com.mtcc.project.extractor.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
@RequiredArgsConstructor
public class GitHubClientConfig {

    private static final int MAX_FILE_BYTES = 5 * 1024 * 1024;

    private final GitHubClientProperties gitHubClientProperties;

    @Bean
    public WebClient gitHubClient(final WebClient.Builder webClientBuilder) {
        return webClientBuilder
                .baseUrl(gitHubClientProperties.getGithubApiUrl())
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_FILE_BYTES))
                .build();
    }
}
