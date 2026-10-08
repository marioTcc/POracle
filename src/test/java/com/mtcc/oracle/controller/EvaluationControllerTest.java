package com.mtcc.oracle.controller;

import com.mtcc.oracle.entity.Dataset;
import com.mtcc.oracle.exception.EvaluationAlreadyRunningException;
import com.mtcc.oracle.exception.InvalidDatasetException;
import com.mtcc.oracle.service.EvaluationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvaluationControllerTest {

    @Mock
    private EvaluationService evaluationService;
    @InjectMocks
    private EvaluationController evaluationController;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(evaluationController).build();
    }

    @Test
    void uploadDataset_ShouldUploadTheDatasetOfTheRequest() {
        when(evaluationService.uploadDataset(any())).thenReturn(Mono.empty());

        webTestClient.post().uri("/evaluation/upload-dataset")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"datasetName": "golden", "datasetDescription": "Golden dataset",
                         "records": [{"category": "scope", "question": "Which projects?", "answer": "Two."}]}""")
                .exchange()
                .expectStatus().isNoContent();

        final ArgumentCaptor<Dataset> dataset = ArgumentCaptor.forClass(Dataset.class);
        verify(evaluationService).uploadDataset(dataset.capture());
        assertEquals("golden", dataset.getValue().getDatasetName());
        assertEquals("Which projects?", dataset.getValue().getRecords().get(0).getQuestion());
    }

    @Test
    void uploadDataset_ShouldAnswerBadRequest_WhenTheDatasetIsNotValid() {
        when(evaluationService.uploadDataset(any())).thenReturn(Mono.error(new InvalidDatasetException("The dataset has no name")));

        webTestClient.post().uri("/evaluation/upload-dataset")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void run_ShouldStartTheEvaluationOfTheGivenDataset() {
        when(evaluationService.startRun("golden")).thenReturn(Mono.empty());

        webTestClient.post().uri(uri -> uri.path("/evaluation/run").queryParam("dataset", "golden").build())
                .exchange()
                .expectStatus().isAccepted();

        verify(evaluationService).startRun("golden");
    }

    @Test
    void run_ShouldStartTheEvaluationOfTheDefaultDataset_WhenNoneIsGiven() {
        when(evaluationService.startRun(null)).thenReturn(Mono.empty());

        webTestClient.post().uri("/evaluation/run")
                .exchange()
                .expectStatus().isAccepted();

        verify(evaluationService).startRun(null);
    }

    @Test
    void run_ShouldAnswerConflict_WhenAnEvaluationIsAlreadyRunning() {
        when(evaluationService.startRun(any())).thenReturn(Mono.error(new EvaluationAlreadyRunningException()));

        webTestClient.post().uri("/evaluation/run")
                .exchange()
                .expectStatus().isEqualTo(409);
    }
}
