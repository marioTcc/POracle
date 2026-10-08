package com.mtcc.oracle.controller;

import com.mtcc.oracle.service.AgentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping(value = "/agent", produces = MediaType.APPLICATION_JSON_VALUE)
public class AgentController {

    private final AgentService agentService;

    @PostMapping(value = "/ask")
    public Mono<String> ask(@RequestBody final String question) {
        return agentService.answer(question);
    }
}
