package com.mtcc.oracle.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Base64;

@Getter
@Component
public class LangfuseClientProperties {

    @Value("${LANGFUSE.HOST}")
    private String langfuseHost;

    @Value("${LANGFUSE.SECRET.KEY}")
    private String langfuseSecretKey;

    @Value("${LANGFUSE.PUBLIC.KEY}")
    private String langfusePublicKey;

    @Value("${LANGFUSE.TRACING.ENABLED:true}")
    private Boolean isTracingEnabled;

    public String getAuthHeader() {
        return Base64.getEncoder().encodeToString((langfusePublicKey + ":" + langfuseSecretKey).getBytes());
    }
}
