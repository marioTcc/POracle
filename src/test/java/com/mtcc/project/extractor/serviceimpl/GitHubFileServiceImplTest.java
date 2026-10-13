package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.project.extractor.config.GitHubClientProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GitHubFileServiceImplTest {

    private static final String REPOSITORY_URL = "https://github.com/spring-projects/spring-ai.git";

    private final GitHubClientProperties properties = mock(GitHubClientProperties.class);
    private final List<ClientRequest> requests = new CopyOnWriteArrayList<>();

    private HttpStatus responseStatus = HttpStatus.OK;
    private GitHubFileServiceImpl gitHubFileService;

    @BeforeEach
    void setUp() {
        when(properties.getGithubApiKey()).thenReturn("secret-token");

        final WebClient gitHubClient = WebClient.builder()
                .baseUrl("https://api.github.test")
                .exchangeFunction(request -> {
                    requests.add(request);
                    return Mono.just(ClientResponse.create(responseStatus).body("class OpenAiApi {}").build());
                })
                .build();
        gitHubFileService = new GitHubFileServiceImpl(gitHubClient, properties);
    }

    @Test
    void readFile_ShouldAskForTheRawFileAtTheRevision() {
        final String content = gitHubFileService.readFile(REPOSITORY_URL, "4f2c9d1", "models/openai/OpenAiApi.java").block();

        assertEquals("class OpenAiApi {}", content);
        assertEquals("https://api.github.test/repos/spring-projects/spring-ai/contents/models/openai/OpenAiApi.java?ref=4f2c9d1",
                requests.get(0).url().toString());
        assertEquals("application/vnd.github.raw+json", requests.get(0).headers().getFirst(HttpHeaders.ACCEPT));
        assertEquals("Bearer secret-token", requests.get(0).headers().getFirst(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void readFile_ShouldAskForTheDefaultBranch_WhenTheRevisionIsNotKnown() {
        gitHubFileService.readFile("https://github.com/spring-projects/spring-ai", "", "README.md").block();

        assertTrue(requests.get(0).url().toString().endsWith("/repos/spring-projects/spring-ai/contents/README.md?ref=HEAD"));
    }

    @Test
    void readFile_ShouldNotSendAnEmptyToken() {
        when(properties.getGithubApiKey()).thenReturn("");

        gitHubFileService.readFile(REPOSITORY_URL, "4f2c9d1", "README.md").block();

        assertFalse(requests.get(0).headers().containsKey(HttpHeaders.AUTHORIZATION));
    }

    @Test
    void readFile_ShouldFail_WhenTheFileDoesNotExist() {
        responseStatus = HttpStatus.NOT_FOUND;

        assertThrows(WebClientResponseException.NotFound.class,
                () -> gitHubFileService.readFile(REPOSITORY_URL, "4f2c9d1", "missing.java").block());
    }

    @Test
    void readFile_ShouldFailWithoutCallingAnything_WhenTheRepositoryIsNotOnGitHub() {
        assertThrows(IllegalArgumentException.class,
                () -> gitHubFileService.readFile("https://gitlab.com/sample/project.git", "4f2c9d1", "README.md").block());
        assertTrue(requests.isEmpty());
    }
}
