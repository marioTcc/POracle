package com.mtcc.oracle.guardrail;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class GuardrailProperties {

    @Value("${GUARDRAILS.ENABLED:true}")
    private Boolean isEnabled;

    @Value("${GUARDRAILS.SELF.CHECK.ENABLED:false}")
    private Boolean isSelfCheckEnabled;

    @Value("${GUARDRAILS.MAX.INPUT.CHARS:10000}")
    private Integer maxInputChars;

    @Value("${GUARDRAILS.TOPICS:the software projects in the knowledge base (their source code, documentation, configuration and architecture)}")
    private String topics;
}
