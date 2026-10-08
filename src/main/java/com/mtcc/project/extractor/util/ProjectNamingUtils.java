package com.mtcc.project.extractor.util;

import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

public class ProjectNamingUtils {

    private static final Pattern VALID_PROJECT_NAME_REGEX = Pattern.compile("(?!\\.+$)[\\w.-]+");

    private ProjectNamingUtils() {}

    public static final UnaryOperator<String> toWikiUrl = gitUrl -> gitUrl
            .replaceAll("\\.git$", "")
            .concat(".wiki.git");

    public static final UnaryOperator<String> urltoProjectName = projectUrl -> projectUrl
            .substring(projectUrl.lastIndexOf("/") + 1)
            .replaceAll("\\.git$", "");

    public static final UnaryOperator<String> urlToProjectWikiName = projectUrl -> urltoProjectName
            .apply(projectUrl)
            .concat("_wiki");

    public static boolean isValidProjectName(final String projectName) {
        return projectName != null && VALID_PROJECT_NAME_REGEX.matcher(projectName).matches();
    }
}
