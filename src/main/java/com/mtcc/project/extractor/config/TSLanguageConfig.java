package com.mtcc.project.extractor.config;

import lombok.Getter;
import org.treesitter.TSLanguage;
import org.treesitter.TSParser;
import org.treesitter.TSQuery;

@Getter
public class TSLanguageConfig {
    private final TSLanguage language;
    private final TSParser parser;
    private final TSQuery query;

    public TSLanguageConfig(final TSLanguage language, final String query) {
        this.language = language;
        this.parser = new TSParser();
        this.parser.setLanguage(language);
        this.query = new TSQuery(language, query);
    }
}
