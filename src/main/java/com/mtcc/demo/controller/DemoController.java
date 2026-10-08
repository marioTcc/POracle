package com.mtcc.demo.controller;

import com.mtcc.oracle.service.EvaluationService;
import com.mtcc.project.extractor.service.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/demo")
public class DemoController {

    private final IngestionService ingestionService;
    private final EvaluationService evaluationService;

    @PostMapping(value = "/ingestion/ingest-spring-projects")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> ingestSpringProjects() {
        return ingestionService.startDemo();
    }

    @PostMapping(value = "/evaluation/upload-golden-demo-dataset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> uploadGoldenDemoDataset() {
        return evaluationService.uploadDefaultDataset();
    }
}
