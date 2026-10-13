package com.mtcc.project.extractor.entity;

import com.mtcc.common.entity.InformationChunk;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class ProjectFolder {

    private String path;
    @Builder.Default
    private List<String> subfolders = List.of();
    @Builder.Default
    private List<InformationChunk> files = List.of();
}
