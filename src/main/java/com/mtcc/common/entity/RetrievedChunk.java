package com.mtcc.common.entity;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class RetrievedChunk {

    private InformationChunk chunk;
    private double score;
}
