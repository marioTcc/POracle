package com.mtcc.oracle.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mtcc.oracle.entity.AgentAnswer;
import com.mtcc.oracle.exception.RequestBlockedException;
import com.mtcc.oracle.guardrail.GuardrailPrompts;
import com.mtcc.oracle.serviceimpl.interfaces.IAgentServiceImpl;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class AgentService {

    private static final String MESSAGE_FIELD = "message";

    private final IAgentServiceImpl agentServiceImpl;
    private final ObjectMapper objectMapper;

    public Mono<String> answer(final String request) {
        return answerWithSources(request).map(AgentAnswer::getText);
    }

    public Mono<AgentAnswer> answerWithSources(final String request) {
        return Mono.defer(() -> agentServiceImpl.answerQuestion(getQuestion(request)))
                .onErrorResume(RequestBlockedException.class,
                        error -> Mono.just(AgentAnswer.builder().text(GuardrailPrompts.REFUSAL_MESSAGE).build()));
    }

    public boolean isRefusal(final AgentAnswer answer) {
        return GuardrailPrompts.REFUSAL_MESSAGE.equals(answer.getText());
    }

    public Mono<String> summarizeProject(final String projectName, final String documentation) {
        return agentServiceImpl.summarizeProject(projectName, documentation);
    }

    public Mono<String> summarizeModule(final String projectName, final String modulePath, final String documentation) {
        return agentServiceImpl.summarizeModule(projectName, modulePath, documentation);
    }

    public Mono<String> analyzeProjectStructure(final String projectStructure) {
        return agentServiceImpl.analyzeProjectStructure(projectStructure);
    }

    public Mono<Double> judgeAnswerRecall(final String question, final String expectedAnswer, final String answer) {
        return agentServiceImpl.judgeAnswerRecall(question, expectedAnswer, answer);
    }

    public Mono<Double> judgeGroundedness(final String question, final String sources, final String answer) {
        return agentServiceImpl.judgeGroundedness(question, sources, answer);
    }

    public Mono<Double> judgeContextPrecision(final String question, final String sources) {
        return agentServiceImpl.judgeContextPrecision(question, sources);
    }

    public Mono<Double> judgeContextRecall(final String question, final String expectedAnswer, final String sources) {
        return agentServiceImpl.judgeContextRecall(question, expectedAnswer, sources);
    }

    private String getQuestion(final String request) {
        return Try.of(() -> objectMapper.readTree(request))
                .filter(json -> json.isObject() && json.path(MESSAGE_FIELD).isTextual())
                .map(json -> json.path(MESSAGE_FIELD).asText())
                .getOrElse(request);
    }
}
