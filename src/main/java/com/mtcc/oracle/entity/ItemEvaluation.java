package com.mtcc.oracle.entity;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
public class ItemEvaluation {

    private String category;
    private List<EvaluationScore> scores;
}
