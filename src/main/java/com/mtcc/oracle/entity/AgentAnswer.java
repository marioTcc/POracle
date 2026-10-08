package com.mtcc.oracle.entity;

import com.mtcc.common.entity.InformationChunk;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Builder
@Getter
public class AgentAnswer {

    private String text;
    @Builder.Default
    private List<InformationChunk> sources = List.of();
}
