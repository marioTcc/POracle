package com.mtcc.rag.controller;

import com.mtcc.rag.exception.CollectionNotFoundException;
import com.mtcc.rag.service.RagService;
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
class RagControllerTest {

    private static final String COLLECTION = "projects_collection";

    @Mock
    private RagService ragService;
    @InjectMocks
    private RagController ragController;

    private WebTestClient webTestClient;

    @BeforeEach
    void setUp() {
        webTestClient = WebTestClient.bindToController(ragController).build();
    }

    @Test
    void deleteCollection_ShouldDeleteTheGivenCollection() {
        when(ragService.deleteCollection(COLLECTION)).thenReturn(Mono.empty());

        webTestClient.delete().uri(uri -> uri.path("/rag/delete-collection").queryParam("collection", COLLECTION).build())
                .exchange()
                .expectStatus().isNoContent();

        verify(ragService).deleteCollection(COLLECTION);
    }

    @Test
    void deleteCollection_ShouldReturnNotFound_WhenTheCollectionDoesNotExist() {
        when(ragService.deleteCollection(COLLECTION)).thenReturn(Mono.error(new CollectionNotFoundException(COLLECTION)));

        webTestClient.delete().uri(uri -> uri.path("/rag/delete-collection").queryParam("collection", COLLECTION).build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void deleteCollection_ShouldReturnBadRequest_WhenTheCollectionIsMissing() {
        webTestClient.delete().uri("/rag/delete-collection")
                .exchange()
                .expectStatus().isBadRequest();
    }
}
