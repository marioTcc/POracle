package com.mtcc.oracle.observability;

public interface GenerationTrace {

    void complete(final String output, final Integer inputTokens, final Integer outputTokens);
    void fail(final Throwable error);
}
