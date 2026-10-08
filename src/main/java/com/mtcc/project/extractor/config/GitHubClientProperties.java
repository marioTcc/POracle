package com.mtcc.project.extractor.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Getter
@Component
public class GitHubClientProperties {

    @Value("${GITHUB.API.KEY}")
    private String githubApiKey;

    @Value("${GITHUB.CLONE.ALL.BRANCHES}")
    private Boolean cloneAllBranches;

    @Value("${GITHUB.CLONE.DEPTH}")
    private Integer cloneDepth;
}
