package com.mtcc.project.extractor.serviceimpl.section;

import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@RequiredArgsConstructor
public class SectionPacker {

    private static final String PATH_SEPARATOR = " > ";

    private final int maxChars;

    public List<PackedSection> pack(final TextSection root) {
        final List<PackedSection> packed = new ArrayList<>();

        pack(root, root.getLabel(), packed);
        return packed;
    }

    private void pack(final TextSection section, final String path, final List<PackedSection> packed) {
        final List<TextSection> children = section.getChildren().isEmpty() ? getLines(section) : section.getChildren();

        if (section.getText().length() <= maxChars || children.isEmpty()) {
            packed.add(PackedSection.builder().context(path).text(section.getText()).build());
            return;
        }

        final List<TextSection> group = new ArrayList<>();
        int groupChars = 0;

        for (final TextSection child : children) {
            final int childChars = child.getText().length();

            if (childChars > maxChars || groupChars + section.getChildSeparator().length() + childChars > maxChars) {
                packGroup(group, section.getChildSeparator(), path, packed);
                groupChars = 0;
            }

            if (childChars > maxChars) {
                pack(child, getPath(path, child), packed);
            } else {
                groupChars += (group.isEmpty() ? 0 : section.getChildSeparator().length()) + childChars;
                group.add(child);
            }
        }
        packGroup(group, section.getChildSeparator(), path, packed);
    }

    private void packGroup(final List<TextSection> group, final String separator, final String path,
                           final List<PackedSection> packed) {
        if (group.isEmpty()) {
            return;
        }

        packed.add(PackedSection.builder()
                .context(group.size() == 1 ? getPath(path, group.get(0)) : path)
                .text(String.join(separator, group.stream().map(TextSection::getText).toList()))
                .build());
        group.clear();
    }

    private List<TextSection> getLines(final TextSection section) {
        final List<String> lines = section.getText().lines().toList();

        return lines.size() < 2 ? List.of() : lines.stream()
                .map(line -> TextSection.builder().text(line).build())
                .toList();
    }

    private String getPath(final String parentPath, final TextSection child) {
        if (child.getLabel().isEmpty()) {
            return parentPath;
        }
        return parentPath.isEmpty() ? child.getLabel() : parentPath + PATH_SEPARATOR + child.getLabel();
    }
}
