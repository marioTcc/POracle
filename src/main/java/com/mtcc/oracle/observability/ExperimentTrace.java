package com.mtcc.oracle.observability;

import reactor.util.context.Context;

public interface ExperimentTrace {

    String getTraceId();
    String getObservationId();
    Context addTo(final Context subscriberContext);
    void setOutput(final String output);
    void end();
}
