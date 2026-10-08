package com.mtcc.oracle.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class AgentProperties {

    @Value("${OLLAMA.BASE.URL}")
    private String ollamaBaseUrl;

    @Value("${AGENT.LOG.REQUESTS:false}")
    private Boolean isRequestLoggingEnabled;

    @Value("${AGENT.LOG.RESPONSES:false}")
    private Boolean isResponseLoggingEnabled;

    @Value("${OLLAMA.GENERAL.PURPOSE.MODEL.NAME}")
    private String ollamaGeneralPurposeModelName;

    @Value("${OLLAMA.CODING.MODEL.NAME}")
    private String ollamaCodingModelName;

    @Value("${OLLAMA.JUDGE.MODEL.NAME}")
    private String ollamaJudgeModelName;

    @Value("${RAG.SEARCH.MAX.CHUNK.CHARS:1500}")
    private Integer searchMaxChunkChars;

    @Value("${RAG.SEARCH.WEAK.SCORE:#{null}}")
    private Double searchWeakScore;
}
