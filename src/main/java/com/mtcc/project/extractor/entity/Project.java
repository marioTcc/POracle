package com.mtcc.project.extractor.entity;

import lombok.Builder;
import lombok.Getter;

import java.nio.file.Path;

@Getter
@Builder(toBuilder = true)
public class Project {

    private String projectName;
    private String projectUrl;
    private Path projectPath;
    private Path projectRepoWikiPath;
}
