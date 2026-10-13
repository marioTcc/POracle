package com.mtcc.project.extractor.util;

import org.apache.commons.lang3.StringUtils;

public class OutlineUtils {

    private static final String CUT_MARK = "\n...";

    private OutlineUtils() {}

    public static String keepFirstLines(final String text, final int maxChars) {
        final String stripped = text.strip();

        if (stripped.length() <= maxChars) {
            return stripped;
        }

        final String head = StringUtils.left(stripped, maxChars);
        final int lastLineEnd = head.lastIndexOf('\n');

        return (lastLineEnd > 0 ? head.substring(0, lastLineEnd).stripTrailing() : head) + CUT_MARK;
    }
}
