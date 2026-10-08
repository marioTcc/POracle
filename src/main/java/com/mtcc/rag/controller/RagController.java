package com.mtcc.rag.controller;

import com.mtcc.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/rag")
public class RagController {

    private final RagService ragService;

    @DeleteMapping(value = "/delete-collection")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteCollection(@RequestParam final String collection) {
        return ragService.deleteCollection(collection);
    }
}
