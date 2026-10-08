package com.mtcc.oracle.serviceimpl.interfaces;

import com.mtcc.oracle.entity.AgentAnswer;
import reactor.core.publisher.Mono;

public interface IAgentServiceImpl {

    Mono<AgentAnswer> answerQuestion(final String question);
    Mono<String> summarizeProject(final String projectName, final String documentation);
    Mono<String> summarizeModule(final String projectName, final String modulePath, final String documentation);
    Mono<String> analyzeProjectStructure(final String projectStructure);
    Mono<Double> judgeAnswerRecall(final String question, final String expectedAnswer, final String answer);
    Mono<Double> judgeGroundedness(final String question, final String sources, final String answer);
    Mono<Double> judgeContextPrecision(final String question, final String sources);
    Mono<Double> judgeContextRecall(final String question, final String expectedAnswer, final String sources);
}
