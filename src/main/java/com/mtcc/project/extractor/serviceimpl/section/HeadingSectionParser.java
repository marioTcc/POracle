package com.mtcc.project.extractor.serviceimpl.section;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class HeadingSectionParser implements SectionParser {

    public enum Style { MARKDOWN, ASCIIDOC, RESTRUCTURED_TEXT, PLAIN }

    private static final String BLOCK_SEPARATOR = "\n\n";
    private static final String CODE_FENCE = "```";
    private static final String TILDE_FENCE = "~~~";
    private static final String ASCIIDOC_DELIMITERS = "-.+=*_";
    private static final String UNDERLINES = "=-~^\"'`#*+.:_";
    private static final int MAX_HEADING_LEVEL = 6;
    private static final int MIN_DELIMITER_CHARS = 4;
    private static final int MIN_UNDERLINE_CHARS = 3;

    private final Style style;

    @Override
    public TextSection parse(final String content) {
        final List<String> lines = content.lines().toList();
        final List<Boolean> fenced = findFencedLines(lines);

        return buildSection("", 0, 0, lines.size(), lines, fenced, findHeadings(lines, fenced));
    }

    private TextSection buildSection(final String label, final int from, final int bodyFrom, final int to,
                                     final List<String> lines, final List<Boolean> fenced, final List<Heading> headings) {
        final List<Heading> innerHeadings = headings.stream()
                .filter(heading -> heading.getStart() >= bodyFrom && heading.getStart() < to)
                .toList();
        final int ownTo = innerHeadings.isEmpty() ? to : innerHeadings.get(0).getStart();
        final List<TextSection> children = new ArrayList<>(getBlocks(lines, fenced, from, bodyFrom, ownTo));

        int next = 0;
        while (next < innerHeadings.size()) {
            final Heading child = innerHeadings.get(next);
            int following = next + 1;

            while (following < innerHeadings.size() && innerHeadings.get(following).getLevel() > child.getLevel()) {
                following++;
            }

            children.add(buildSection(child.getTitle(), child.getStart(), child.getEnd(),
                    following < innerHeadings.size() ? innerHeadings.get(following).getStart() : to,
                    lines, fenced, innerHeadings));
            next = following;
        }

        return TextSection.builder()
                .label(label)
                .text(join(lines, from, to))
                .children(children)
                .childSeparator(BLOCK_SEPARATOR)
                .build();
    }

    private List<TextSection> getBlocks(final List<String> lines, final List<Boolean> fenced,
                                        final int from, final int bodyFrom, final int to) {
        final List<String> blocks = new ArrayList<>();
        int blockFrom = bodyFrom;

        for (int index = bodyFrom; index <= to; index++) {
            if (index == to || (lines.get(index).isBlank() && !Boolean.TRUE.equals(fenced.get(index)))) {
                final String block = join(lines, blockFrom, index);

                if (!block.isEmpty()) {
                    blocks.add(block);
                }
                blockFrom = index + 1;
            }
        }

        if (!blocks.isEmpty() && from < bodyFrom) {
            blocks.set(0, join(lines, from, bodyFrom) + BLOCK_SEPARATOR + blocks.get(0));
        }
        return blocks.stream()
                .map(block -> TextSection.builder().text(block).build())
                .toList();
    }

    private String join(final List<String> lines, final int from, final int to) {
        return from >= to ? "" : String.join("\n", lines.subList(from, to)).strip();
    }

    private List<Boolean> findFencedLines(final List<String> lines) {
        final List<Boolean> fenced = new ArrayList<>();
        String openFence = null;

        for (final String line : lines) {
            final String fence = getFence(line.strip());

            fenced.add(openFence != null || fence != null);
            if (openFence == null) {
                openFence = fence;
            } else if (openFence.equals(fence)) {
                openFence = null;
            }
        }
        return fenced;
    }

    private String getFence(final String line) {
        if (style == Style.MARKDOWN || style == Style.ASCIIDOC) {
            if (line.startsWith(CODE_FENCE)) {
                return CODE_FENCE;
            }
            if (style == Style.MARKDOWN && line.startsWith(TILDE_FENCE)) {
                return TILDE_FENCE;
            }
            if (style == Style.ASCIIDOC && line.length() >= MIN_DELIMITER_CHARS && isRepeatedChar(line, ASCIIDOC_DELIMITERS)) {
                return line;
            }
        }
        return null;
    }

    private List<Heading> findHeadings(final List<String> lines, final List<Boolean> fenced) {
        final List<Heading> headings = new ArrayList<>();
        final List<Character> underlines = new ArrayList<>();
        int index = 0;

        while (index < lines.size()) {
            final Heading heading = Boolean.TRUE.equals(fenced.get(index)) ? null : getHeading(lines, index, underlines);

            if (heading == null) {
                index++;
            } else {
                headings.add(heading);
                index = heading.getEnd();
            }
        }
        return headings;
    }

    private Heading getHeading(final List<String> lines, final int index, final List<Character> underlines) {
        return switch (style) {
            case MARKDOWN -> getPrefixedHeading(lines.get(index), '#', index);
            case ASCIIDOC -> getPrefixedHeading(lines.get(index), '=', index);
            case RESTRUCTURED_TEXT -> getUnderlinedHeading(lines, index, underlines);
            case PLAIN -> null;
        };
    }

    private Heading getPrefixedHeading(final String line, final char marker, final int index) {
        int level = 0;
        while (level < line.length() && line.charAt(level) == marker) {
            level++;
        }

        if (level == 0 || level > MAX_HEADING_LEVEL || level >= line.length() || !Character.isWhitespace(line.charAt(level))) {
            return null;
        }

        final String title = StringUtils.strip(line.substring(level), " \t" + marker);
        return title.isEmpty() ? null : Heading.builder().level(level).title(title).start(index).end(index + 1).build();
    }

    private Heading getUnderlinedHeading(final List<String> lines, final int index, final List<Character> underlines) {
        final String line = lines.get(index).strip();
        final String nextLine = index + 1 < lines.size() ? lines.get(index + 1).strip() : "";
        final String thirdLine = index + 2 < lines.size() ? lines.get(index + 2).strip() : "";

        if (isUnderline(line) && !nextLine.isEmpty() && !isUnderline(nextLine) && thirdLine.equals(line)) {
            return buildUnderlinedHeading(nextLine, line.charAt(0), index, index + 3, underlines);
        }
        if (!line.isEmpty() && !isUnderline(line) && isUnderline(nextLine) && nextLine.length() >= line.length()) {
            return buildUnderlinedHeading(line, nextLine.charAt(0), index, index + 2, underlines);
        }
        return null;
    }

    private Heading buildUnderlinedHeading(final String title, final char underline, final int start, final int end,
                                           final List<Character> underlines) {
        if (!underlines.contains(underline)) {
            underlines.add(underline);
        }
        return Heading.builder().level(underlines.indexOf(underline) + 1).title(title).start(start).end(end).build();
    }

    private boolean isUnderline(final String line) {
        return line.length() >= MIN_UNDERLINE_CHARS && isRepeatedChar(line, UNDERLINES);
    }

    private boolean isRepeatedChar(final String line, final String allowedChars) {
        return allowedChars.indexOf(line.charAt(0)) >= 0 && line.chars().allMatch(character -> character == line.charAt(0));
    }

    @Getter
    @Builder
    private static class Heading {

        private int level;
        private String title;
        private int start;
        private int end;
    }
}
