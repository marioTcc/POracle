package com.mtcc.project.extractor.serviceimpl.section;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

public class PropertiesSectionParser implements SectionParser {

    private static final String COMMENTS = "#!";
    private static final String KEY_ENDS = "=: \t";
    private static final String KEY_SEPARATOR = ".";

    @Override
    public TextSection parse(final String content) {
        final List<TextSection> groups = new ArrayList<>();
        final List<String> groupLines = new ArrayList<>();
        final List<String> pendingLines = new ArrayList<>();
        String groupPrefix = null;
        boolean isContinuation = false;

        for (final String line : content.lines().toList()) {
            if (isContinuation) {
                groupLines.add(line);
                isContinuation = endsWithContinuation(line);
            } else if (line.isBlank() || COMMENTS.indexOf(line.strip().charAt(0)) >= 0) {
                pendingLines.add(line);
            } else {
                final String prefix = getPrefix(line);

                if (!prefix.equals(groupPrefix)) {
                    addGroup(groups, groupPrefix, groupLines);
                    groupPrefix = prefix;
                }
                groupLines.addAll(pendingLines);
                groupLines.add(line);
                pendingLines.clear();
                isContinuation = endsWithContinuation(line);
            }
        }

        groupLines.addAll(groupPrefix == null ? List.of() : pendingLines);
        addGroup(groups, groupPrefix, groupLines);

        return TextSection.builder()
                .text(content.strip())
                .children(groups)
                .build();
    }

    private void addGroup(final List<TextSection> groups, final String prefix, final List<String> lines) {
        if (!lines.isEmpty()) {
            groups.add(TextSection.builder()
                    .label(prefix)
                    .text(String.join("\n", lines).strip())
                    .build());
            lines.clear();
        }
    }

    private String getPrefix(final String line) {
        final String entry = line.stripLeading();
        final int keyEnd = StringUtils.indexOfAny(entry, KEY_ENDS);

        return StringUtils.substringBefore(keyEnd < 0 ? entry : entry.substring(0, keyEnd), KEY_SEPARATOR);
    }

    private boolean endsWithContinuation(final String line) {
        return (line.length() - StringUtils.stripEnd(line, "\\").length()) % 2 == 1;
    }
}
