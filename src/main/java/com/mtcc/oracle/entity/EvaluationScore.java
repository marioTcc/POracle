package com.mtcc.oracle.entity;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class EvaluationScore {

    private String name;
    private double value;
}
