package com.mtcc.oracle.entity;

import com.mtcc.common.entity.InformationChunk;
import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class ProjectFile {

    private InformationChunk node;
    private String content;
    private boolean folder;
}
