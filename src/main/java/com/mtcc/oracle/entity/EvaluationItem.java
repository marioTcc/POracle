package com.mtcc.oracle.entity;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class EvaluationItem {

    private String id;
    private String datasetId;
    private String input;
    private String expectedOutput;
    private String category;
}
