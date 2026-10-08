package com.mtcc.demo.controller;

import com.mtcc.oracle.service.EvaluationService;
import com.mtcc.project.extractor.exception.IngestionAlreadyRunningException;
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
class DemoControllerTest {

    @Mock
    private IngestionService ingestionService;
    @Mock
    private EvaluationService evaluationService;
    @InjectMocks
    private DemoController demoController;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(demoController).build();
    }

    @Test
    void ingestSpringProjects_ShouldStartTheDemoIngestion() {
        when(ingestionService.startDemo()).thenReturn(Mono.empty());

        webTestClient.post().uri("/demo/ingestion/ingest-spring-projects")
                .exchange()
                .expectStatus().isAccepted();

        verify(ingestionService).startDemo();
    }

    @Test
    void ingestSpringProjects_ShouldReturnConflict_WhenAnIngestionIsAlreadyRunning() {
        when(ingestionService.startDemo()).thenReturn(Mono.error(new IngestionAlreadyRunningException()));

        webTestClient.post().uri("/demo/ingestion/ingest-spring-projects")
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void uploadGoldenDemoDataset_ShouldUploadTheBundledDataset() {
        when(evaluationService.uploadDefaultDataset()).thenReturn(Mono.empty());

        webTestClient.post().uri("/demo/evaluation/upload-golden-demo-dataset")
                .exchange()
                .expectStatus().isNoContent();

        verify(evaluationService).uploadDefaultDataset();
    }
}
