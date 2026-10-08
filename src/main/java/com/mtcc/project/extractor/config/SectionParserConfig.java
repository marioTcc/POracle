package com.mtcc.project.extractor.config;

import com.mtcc.project.extractor.serviceimpl.section.HeadingSectionParser;
import com.mtcc.project.extractor.serviceimpl.section.IndentationSectionParser;
import com.mtcc.project.extractor.serviceimpl.section.JsonSectionParser;
import com.mtcc.project.extractor.serviceimpl.section.PropertiesSectionParser;
import com.mtcc.project.extractor.serviceimpl.section.SectionParser;
import com.mtcc.project.extractor.serviceimpl.section.XmlSectionParser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class SectionParserConfig {

    public static final String ANY_EXTENSION = "*";

    private static final SectionParser PARAGRAPHS = new HeadingSectionParser(HeadingSectionParser.Style.PLAIN);
    private static final SectionParser MARKDOWN = new HeadingSectionParser(HeadingSectionParser.Style.MARKDOWN);
    private static final SectionParser ASCIIDOC = new HeadingSectionParser(HeadingSectionParser.Style.ASCIIDOC);
    private static final SectionParser RESTRUCTURED_TEXT = new HeadingSectionParser(HeadingSectionParser.Style.RESTRUCTURED_TEXT);
    private static final SectionParser YAML = new IndentationSectionParser();
    private static final SectionParser PROPERTIES = new PropertiesSectionParser();
    private static final SectionParser JSON = new JsonSectionParser();
    private static final SectionParser XML = new XmlSectionParser();

    @Bean
    public Map<String, SectionParser> sectionParsers() {
        return Map.of(
                "md", MARKDOWN,
                "adoc", ASCIIDOC,
                "asciidoc", ASCIIDOC,
                "rst", RESTRUCTURED_TEXT,
                "yaml", YAML,
                "yml", YAML,
                "properties", PROPERTIES,
                "json", JSON,
                "xml", XML,
                ANY_EXTENSION, PARAGRAPHS);
    }
}
