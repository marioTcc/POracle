package com.mtcc.project.extractor.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Getter
@Component
public class ChunkingProperties {

    @Value("${TEXT.CHUNK.MAX.CHARS:1500}")
    private Integer textChunkMaxChars;

    @Value("${CODE.FILE.EXTENSIONS}")
    private List<String> codeFileExtensions;

    @Value("${DOCS.FILE.EXTENSIONS}")
    private List<String> docsFileExtensions;

    @Value("${CONFIG.FILE.EXTENSIONS}")
    private List<String> configFileExtensions;

    @Value("${PROJECT.TREE.MAX.DEPTH:3}")
    private Integer projectTreeMaxDepth;

    @Value("${SUMMARY.MAX.MODULES:40}")
    private Integer summaryMaxModules;

    @Value("${SUMMARY.MODULE.MAX.DEPTH:3}")
    private Integer summaryModuleMaxDepth;

    @Value("${SUMMARY.MAX.INPUT.CHARS:12000}")
    private Integer summaryMaxInputChars;

    @Value("${SECRETS.REDACTION.ENABLED:true}")
    private Boolean secretsRedactionEnabled;

    public Set<String> getSupportedFileExtensions() {
        return Stream.of(codeFileExtensions, docsFileExtensions, configFileExtensions)
                .flatMap(List::stream)
                .collect(Collectors.toSet());
    }
}
