package com.mtcc.project.extractor.serviceimpl.section;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class PackedSection {

    private String context;
    private String text;
}
