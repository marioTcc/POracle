package com.mtcc.project.extractor.serviceimpl.section;

import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.stream.IntStream;

public class IndentationSectionParser implements SectionParser {

    private static final String COMMENT = "#";
    private static final String LIST_ITEM = "- ";
    private static final String KEY_END = ": ";

    @Override
    public TextSection parse(final String content) {
        final List<String> lines = content.lines().toList();

        return TextSection.builder()
                .text(content.strip())
                .children(getChildren(lines, 0, lines.size()))
                .build();
    }

    private List<TextSection> getChildren(final List<String> lines, final int from, final int to) {
        final OptionalInt childIndent = IntStream.range(from, to)
                .filter(index -> isContent(lines.get(index)))
                .map(index -> getIndent(lines.get(index)))
                .min();
        final List<Integer> keyLines = childIndent.isEmpty() ? List.<Integer>of() : IntStream.range(from, to)
                .filter(index -> isContent(lines.get(index)) && getIndent(lines.get(index)) == childIndent.getAsInt())
                .boxed()
                .toList();
        final List<TextSection> children = new ArrayList<>();
        int childFrom = from;

        for (int child = 0; child < keyLines.size(); child++) {
            final int keyLine = keyLines.get(child);
            final int childTo = child + 1 < keyLines.size() ? getCommentStart(lines, keyLines.get(child + 1), keyLine) : to;

            children.add(TextSection.builder()
                    .label(getKey(lines.get(keyLine)))
                    .text(join(lines, childFrom, childTo))
                    .children(getChildren(lines, keyLine + 1, childTo))
                    .build());
            childFrom = childTo;
        }
        return children;
    }

    private int getCommentStart(final List<String> lines, final int keyLine, final int previousKeyLine) {
        int start = keyLine;

        while (start - 1 > previousKeyLine && lines.get(start - 1).strip().startsWith(COMMENT)) {
            start--;
        }
        return start;
    }

    private String join(final List<String> lines, final int from, final int to) {
        int start = from;

        while (start < to && lines.get(start).isBlank()) {
            start++;
        }
        return String.join("\n", lines.subList(start, to)).stripTrailing();
    }

    private boolean isContent(final String line) {
        return !line.isBlank() && !line.strip().startsWith(COMMENT);
    }

    private int getIndent(final String line) {
        return line.length() - line.stripLeading().length();
    }

    private String getKey(final String line) {
        final String entry = StringUtils.removeStart(line.strip(), LIST_ITEM);
        final int keyEnd = entry.endsWith(":") && !entry.contains(KEY_END) ? entry.length() - 1 : entry.indexOf(KEY_END);

        return keyEnd <= 0 ? "" : StringUtils.strip(entry.substring(0, keyEnd), "\"' ");
    }
}
