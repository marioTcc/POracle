package com.mtcc.project.extractor.serviceimpl.section;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import io.vavr.control.Try;
import reactor.core.Exceptions;

import java.util.ArrayList;
import java.util.List;

public class JsonSectionParser implements SectionParser {

    private static final String ELEMENT_SEPARATOR = ",\n";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ObjectWriter prettyWriter = objectMapper.writer(
            new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n")));

    @Override
    public TextSection parse(final String content) {
        final JsonNode root = Try.of(() -> objectMapper.readTree(content))
                .getOrElseThrow(Exceptions::propagate);

        if (!root.isContainerNode()) {
            throw new IllegalArgumentException("The content is not a JSON object or array");
        }
        return buildSection("", content.strip(), root);
    }

    private TextSection buildSection(final String label, final String text, final JsonNode node) {
        final List<TextSection> children = new ArrayList<>();

        if (node.isObject()) {
            node.fields().forEachRemaining(field -> children.add(buildSection(field.getKey(),
                    toJson(field.getKey()) + ": " + toJson(field.getValue()), field.getValue())));
        } else if (node.isArray()) {
            node.elements().forEachRemaining(element -> children.add(buildSection("", toJson(element), element)));
        }

        return TextSection.builder()
                .label(label)
                .text(text)
                .children(children)
                .childSeparator(ELEMENT_SEPARATOR)
                .build();
    }

    private String toJson(final Object value) {
        return Try.of(() -> prettyWriter.writeValueAsString(value))
                .getOrElseThrow(Exceptions::propagate);
    }
}
