package com.mtcc.oracle.controller;

import com.mtcc.oracle.entity.Dataset;
import com.mtcc.oracle.service.EvaluationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/evaluation")
public class EvaluationController {

    private final EvaluationService evaluationService;

    @PostMapping(value = "/upload-dataset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> uploadDataset(@RequestBody final Dataset dataset) {
        return evaluationService.uploadDataset(dataset);
    }

    @PostMapping(value = "/run")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<Void> run(@RequestParam(required = false) final String dataset) {
        return evaluationService.startRun(dataset);
    }
}
