package com.mtcc.oracle.agent;

import com.mtcc.common.security.SecretRedactor;
import com.mtcc.oracle.guardrail.GuardrailPrompts;
import com.mtcc.oracle.guardrail.GuardrailProperties;
import com.mtcc.oracle.guardrail.InputValidationGuardrail;
import com.mtcc.oracle.guardrail.OutputValidationGuardrail;
import com.mtcc.oracle.guardrail.SelfCheckInputGuardrail;
import com.mtcc.oracle.guardrail.SelfCheckOutputGuardrail;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

@Component
@RequiredArgsConstructor
public class AgentFactory {

    private final GuardrailProperties guardrailProperties;
    private final SecretRedactor secretRedactor;

    private final ChatModel generalPurposeModel;

    public <T> T createUserFacingAgent(final Class<T> agentType, final ChatModel chatModel, final Object... tools) {
        return build(AiServices.builder(agentType).chatModel(chatModel).tools(tools), agent -> agent
                .systemMessageTransformer(systemPrompt ->
                        GuardrailPrompts.forUserFacingAgent(systemPrompt, guardrailProperties.getTopics()))
                .inputGuardrails(getInputGuardrails())
                .outputGuardrails(getOutputGuardrails()));
    }

    public <T> T createIngestionAgent(final Class<T> agentType, final ChatModel chatModel) {
        return build(AiServices.builder(agentType).chatModel(chatModel), agent -> agent
                .systemMessageTransformer(GuardrailPrompts::forIngestionAgent));
    }

    public <T> T createJudgeAgent(final Class<T> agentType, final ChatModel chatModel) {
        return build(AiServices.builder(agentType).chatModel(chatModel), agent -> agent
                .systemMessageTransformer(GuardrailPrompts::forIngestionAgent));
    }

    private <T> T build(final AiServices<T> agent, final UnaryOperator<AiServices<T>> guardrails) {
        return Optional.of(agent)
                .filter(unguarded -> Boolean.TRUE.equals(guardrailProperties.getIsEnabled()))
                .map(guardrails)
                .orElse(agent)
                .build();
    }

    private List<InputGuardrail> getInputGuardrails() {
        return withSelfCheck(
                new InputValidationGuardrail(guardrailProperties.getMaxInputChars()),
                () -> new SelfCheckInputGuardrail(generalPurposeModel, guardrailProperties.getTopics()));
    }

    private List<OutputGuardrail> getOutputGuardrails() {
        return withSelfCheck(
                new OutputValidationGuardrail(secretRedactor),
                () -> new SelfCheckOutputGuardrail(generalPurposeModel, guardrailProperties.getTopics()));
    }

    private <G> List<G> withSelfCheck(final G validation, final Supplier<G> selfCheck) {
        return Stream.concat(
                        Stream.of(validation),
                        Stream.of(selfCheck)
                                .filter(check -> Boolean.TRUE.equals(guardrailProperties.getIsSelfCheckEnabled()))
                                .map(Supplier::get))
                .toList();
    }
}
