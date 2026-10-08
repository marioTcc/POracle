package com.mtcc.project.extractor.util;

public final class SummaryTexts {

    public static final String STRATEGY = "llm-summary";
    public static final String LANGUAGE = "TEXT";
    public static final String PROJECT_STRUCTURE_CONTEXT = "project structure";
    public static final String PROJECT_OVERVIEW_CONTEXT = "project overview";

    private static final String MODULE_OVERVIEW_CONTEXT = "module %s";
    private static final String PROJECT_OVERVIEW = "Overview of project %s: %s";
    private static final String MODULE_OVERVIEW = "Overview of module %s of project %s: %s";

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
}
