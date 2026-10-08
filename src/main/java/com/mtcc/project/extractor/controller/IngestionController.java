package com.mtcc.project.extractor.controller;

import com.mtcc.project.extractor.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/ingestion")
public class IngestionController {

    private final IngestionService ingestionService;

    @PostMapping(value = "/ingest-from-path")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> ingestFromPath(@RequestParam final String path) {
        return ingestionService.startIngestFromPath(path);
    }

    @PostMapping(value = "/clone-and-ingest")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> cloneAndIngest(@RequestParam final String repoUrl) {
        return ingestionService.startCloneAndIngest(repoUrl);
    }
}
