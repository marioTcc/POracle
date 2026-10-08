package com.mtcc.oracle.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.oracle.agent.AgentFactory;
import com.mtcc.oracle.agent.CodingAgent;
import com.mtcc.oracle.agent.DocsAnalyzerAgent;
import com.mtcc.oracle.agent.JudgeAgent;
import com.mtcc.oracle.agent.tool.AgentRagTools;
import com.mtcc.oracle.entity.AgentAnswer;
import com.mtcc.oracle.exception.RequestBlockedException;
import com.mtcc.oracle.observability.TraceParentChatModel;
import com.mtcc.oracle.serviceimpl.interfaces.IAgentServiceImpl;
import dev.langchain4j.guardrail.GuardrailException;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class LangChainAgentServiceImpl implements IAgentServiceImpl {

    private static final String POSITIVE_VERDICT = "YES";
    private static final Pattern VERDICT_PATTERN = Pattern.compile(
            "(?im)^.*?=>\\W*(YES|NO)\\b.*$|\\b(YES|NO)\\b\\W*$");

    private final AgentFactory agentFactory;
    private final AgentRagTools agentRagTools;

    private final ChatModel generalPurposeModel;
    private final ChatModel codingModel;
    private final ChatModel judgeModel;

    private CodingAgent codingAgent;
    private DocsAnalyzerAgent docsAnalyzerAgent;

    @PostConstruct
    private void initAgents() {
        codingAgent = agentFactory.createIngestionAgent(CodingAgent.class, codingModel);
        docsAnalyzerAgent = agentFactory.createIngestionAgent(DocsAnalyzerAgent.class, generalPurposeModel);
    }

    @Override
    public Mono<AgentAnswer> answerQuestion(final String question) {
        return Mono.deferContextual(subscriberContext -> {
                    final Map<String, InformationChunk> sources = Collections.synchronizedMap(new LinkedHashMap<>());

                    return agentFactory.createUserFacingAgent(DocsAnalyzerAgent.class,
                                    TraceParentChatModel.of(generalPurposeModel, subscriberContext),
                                    agentRagTools.withQuestion(question).withSourcesListener(chunks -> chunks.forEach(
                                            chunk -> sources.putIfAbsent(getSourceKey(chunk), chunk))))
                            .answerQuestion(question)
                            .map(answer -> AgentAnswer.builder().text(answer).sources(List.copyOf(sources.values())).build());
                })
                .onErrorMap(error -> Exceptions.unwrap(error) instanceof GuardrailException,
                        error -> new RequestBlockedException(Exceptions.unwrap(error).getMessage()))
                .doOnError(error -> log.warn(error instanceof RequestBlockedException ?
                        "Request blocked by the guardrails: {}" : "Cannot answer the question: {}", error.getMessage()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<String> summarizeProject(final String projectName, final String documentation) {
        return docsAnalyzerAgent.summarizeProject(projectName, documentation)
                .doOnSubscribe(subscription -> log.info("Summarizing project {}", projectName))
                .doOnError(error -> log.warn("Cannot summarize project {}: {}", projectName, error.toString()));
    }

    @Override
    public Mono<String> summarizeModule(final String projectName, final String modulePath, final String documentation) {
        return docsAnalyzerAgent.summarizeModule(projectName, modulePath, documentation)
                .doOnSubscribe(subscription -> log.info("Summarizing module {} of project {}", modulePath, projectName))
                .doOnError(error -> log.warn("Cannot summarize module {} of project {}: {}",
                        modulePath, projectName, error.toString()));
    }

    @Override
    public Mono<String> analyzeProjectStructure(final String projectStructure) {
        return codingAgent.analyzeProjectStructure(projectStructure);
    }

    @Override
    public Mono<Double> judgeAnswerRecall(final String question, final String expectedAnswer, final String answer) {
        return judge(judgeAgent -> judgeAgent.evaluateAnswerRecall(question, expectedAnswer, answer));
    }

    @Override
    public Mono<Double> judgeGroundedness(final String question, final String sources, final String answer) {
        return judge(judgeAgent -> judgeAgent.evaluateGroundedness(question, sources, answer));
    }

    @Override
    public Mono<Double> judgeContextPrecision(final String question, final String sources) {
        return judge(judgeAgent -> judgeAgent.evaluateContextPrecision(question, sources));
    }

    @Override
    public Mono<Double> judgeContextRecall(final String question, final String expectedAnswer, final String sources) {
        return judge(judgeAgent -> judgeAgent.evaluateContextRecall(question, expectedAnswer, sources));
    }

    private Mono<Double> judge(final Function<JudgeAgent, Mono<String>> evaluation) {
        return Mono.deferContextual(subscriberContext -> evaluation.apply(agentFactory.createJudgeAgent(JudgeAgent.class,
                        TraceParentChatModel.of(judgeModel, subscriberContext))))
                .flatMap(this::toScore)
                .doOnError(error -> log.warn("Cannot judge the answer: {}", error.toString()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Double> toScore(final String evaluation) {
        final List<String> verdicts = VERDICT_PATTERN.matcher(evaluation).results()
                .map(verdict -> StringUtils.defaultString(verdict.group(1), verdict.group(2)).toUpperCase(Locale.ROOT))
                .toList();

        if (verdicts.isEmpty()) {
            log.warn("The judge did not answer with a verdict per line: {}", evaluation);
            return Mono.empty();
        }
        return Mono.just((double) verdicts.stream().filter(POSITIVE_VERDICT::equals).count() / verdicts.size());
    }

    private String getSourceKey(final InformationChunk chunk) {
        return String.join("|", String.valueOf(chunk.getProjectName()), String.valueOf(chunk.getFilePath()),
                String.valueOf(chunk.getText()));
    }
}
