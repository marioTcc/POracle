package com.mtcc.rag.serviceimpl.converter;

import io.qdrant.client.grpc.Points;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class PrefetchRequest {

    private Points.Query query;
    private String vectorName;
    private String projectName;
    private double minScore;
    private int limit;
}
