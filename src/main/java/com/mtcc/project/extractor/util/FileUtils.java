package com.mtcc.project.extractor.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mtcc.project.extractor.entity.Project;
import io.vavr.control.Try;
import reactor.core.Exceptions;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

public class FileUtils {

    public static final String WIKI_PREFIX = "wiki/";
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());

    private FileUtils() {}

    public static String getLowerCaseExtension(final Path filePath) {
        final String fileName = filePath.getFileName().toString();
        final int lastDotIndex = fileName.lastIndexOf('.');

        return lastDotIndex >= 0 ? fileName.substring(lastDotIndex + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static String getRelativePath(final Project project, final Path filePath) {
        final Path file = filePath.toAbsolutePath().normalize();
        final Path wikiPath = project.getProjectRepoWikiPath();
        final Path projectPath = project.getProjectPath();

        if (wikiPath != null && file.startsWith(wikiPath.toAbsolutePath().normalize())) {
            return WIKI_PREFIX + toUnixPath(wikiPath.toAbsolutePath().normalize().relativize(file));
        }

        if (projectPath != null && file.startsWith(projectPath.toAbsolutePath().normalize())) {
            return toUnixPath(projectPath.toAbsolutePath().normalize().relativize(file));
        }

        return file.getFileName().toString();
    }

    public static boolean isWikiFile(final Project project, final Path filePath) {
        return getRelativePath(project, filePath).startsWith(WIKI_PREFIX);
    }

    public static String toUnixPath(final Path path) {
        return path.toString().replace('\\', '/');
    }

    public static <T> T getObjectFromJsonFilename(final String filePath, final Class<T> type) {
        return Try.of(() -> JSON_MAPPER.readValue(getResource(filePath), type))
                .getOrElseThrow(Exceptions::propagate);
    }

    private static InputStream getResource(final String filePath) throws FileNotFoundException {
        return Optional.ofNullable(FileUtils.class.getClassLoader().getResourceAsStream(filePath))
                .orElseThrow(() -> new FileNotFoundException(filePath));
    }
}
