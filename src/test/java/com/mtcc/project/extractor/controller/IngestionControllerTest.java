package com.mtcc.project.extractor.controller;

import com.mtcc.project.extractor.exception.InvalidIngestionSourceException;
import com.mtcc.project.extractor.service.IngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IngestionControllerTest {

    @Mock
    private IngestionService ingestionService;
    @InjectMocks
    private IngestionController ingestionController;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(ingestionController).build();
    }

    @Test
    void cloneAndIngest_ShouldStartTheIngestionOfTheGivenRepository() {
        final String repoUrl = "https://github.com/spring-projects/spring-ai.git";
        when(ingestionService.startCloneAndIngest(repoUrl)).thenReturn(Mono.empty());

        webTestClient.post().uri(uri -> uri.path("/ingestion/clone-and-ingest").queryParam("repoUrl", repoUrl).build())
                .exchange()
                .expectStatus().isAccepted();

        verify(ingestionService).startCloneAndIngest(repoUrl);
    }

    @Test
    void ingestFromPath_ShouldStartTheIngestionOfTheGivenFolder() {
        final String path = "C:/projects/my-project";
        when(ingestionService.startIngestFromPath(path)).thenReturn(Mono.empty());

        webTestClient.post().uri(uri -> uri.path("/ingestion/ingest-from-path").queryParam("path", path).build())
                .exchange()
                .expectStatus().isAccepted();

        verify(ingestionService).startIngestFromPath(path);
    }

    @Test
    void ingestFromPath_ShouldReturnBadRequest_WhenThePathIsInvalid() {
        when(ingestionService.startIngestFromPath("missing"))
                .thenReturn(Mono.error(new InvalidIngestionSourceException("The path is not an existing directory")));

        webTestClient.post().uri(uri -> uri.path("/ingestion/ingest-from-path").queryParam("path", "missing").build())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void cloneAndIngest_ShouldReturnBadRequest_WhenTheRepositoryUrlIsMissing() {
        webTestClient.post().uri("/ingestion/clone-and-ingest")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
