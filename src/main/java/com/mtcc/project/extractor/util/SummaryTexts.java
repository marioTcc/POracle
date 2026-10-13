package com.mtcc.project.extractor.util;

import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.stream.Collectors;

public final class SummaryTexts {

    public static final String STRATEGY = "llm-summary";
    public static final String LANGUAGE = "TEXT";
    public static final String PROJECT_STRUCTURE_CONTEXT = "project structure";
    public static final String PROJECT_OVERVIEW_CONTEXT = "project overview";
    public static final String ROOT_FOLDER = "/";

    private static final String MODULE_OVERVIEW_CONTEXT = "module %s";
    private static final String FOLDER_OVERVIEW_CONTEXT = "folder %s";
    private static final String PROJECT_OVERVIEW = "Overview of project %s: %s";
    private static final String MODULE_OVERVIEW = "Overview of module %s of project %s: %s";
    private static final String FOLDER_OVERVIEW = "Overview of folder %s of project %s: %s";
    private static final String FOLDER_CONTENTS = "Contents of folder %s of project %s:\n%s";

    private SummaryTexts() {}

    public static String getProjectOverview(final String projectName, final String summary) {
        return PROJECT_OVERVIEW.formatted(projectName, summary);
    }

    public static String getModuleOverview(final String projectName, final String modulePath, final String summary) {
        return MODULE_OVERVIEW.formatted(modulePath, projectName, summary);
    }

    public static String getModuleOverviewContext(final String modulePath) {
        return MODULE_OVERVIEW_CONTEXT.formatted(modulePath);
    }

    public static String getFolderOverview(final String projectName, final String folderPath, final String summary,
                                           final List<String> contentPaths) {
        final String folder = getFolderName(folderPath);
        final String contents = FOLDER_CONTENTS.formatted(folder, projectName, contentPaths.stream()
                .map(path -> "- " + path)
                .collect(Collectors.joining("\n")));

        return StringUtils.isBlank(summary) ?
                contents :
                FOLDER_OVERVIEW.formatted(folder, projectName, summary.strip()) + "\n\n" + contents;
    }

    public static String getFolderOverviewContext(final String folderPath) {
        return folderPath.isEmpty() ? PROJECT_STRUCTURE_CONTEXT : FOLDER_OVERVIEW_CONTEXT.formatted(folderPath);
    }

    public static String getFolderName(final String folderPath) {
        return folderPath.isEmpty() ? ROOT_FOLDER : folderPath;
    }
}
