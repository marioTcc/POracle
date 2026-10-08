package com.mtcc.project.extractor.serviceimpl.section;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class TextSection {

    @Builder.Default
    private String label = "";
    private String text;
    @Builder.Default
    private List<TextSection> children = List.of();
    @Builder.Default
    private String childSeparator = "\n";
}
