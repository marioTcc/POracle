package com.mtcc.project.extractor.util;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.project.extractor.entity.ProjectFolder;
import org.apache.commons.lang3.StringUtils;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProjectTreeUtils {

    public static final String ROOT = "";

    private static final String PATH_SEPARATOR = "/";

    private ProjectTreeUtils() {}

    public static List<ProjectFolder> getFoldersDeepestFirst(final List<InformationChunk> files) {
        if (files.isEmpty()) {
            return List.of();
        }

        final Map<String, List<InformationChunk>> filesByFolder = files.stream()
                .sorted(Comparator.comparing(InformationChunk::getFilePath))
                .collect(Collectors.groupingBy(file -> getParent(file.getFilePath()), TreeMap::new, Collectors.toList()));
        final Map<String, List<String>> subfoldersByFolder = filesByFolder.keySet().stream()
                .flatMap(ProjectTreeUtils::getPathAndAncestors)
                .filter(folder -> !folder.equals(ROOT))
                .collect(Collectors.groupingBy(ProjectTreeUtils::getParent, TreeMap::new,
                        Collectors.collectingAndThen(Collectors.toCollection(TreeSet::new), List::copyOf)));

        return Stream.concat(Stream.of(ROOT), subfoldersByFolder.values().stream().flatMap(List::stream))
                .distinct()
                .filter(folder -> !isPassThrough(folder, filesByFolder, subfoldersByFolder))
                .sorted(Comparator.comparingInt(ProjectTreeUtils::getDepth).reversed().thenComparing(Comparator.naturalOrder()))
                .map(folder -> ProjectFolder.builder()
                        .path(folder)
                        .files(filesByFolder.getOrDefault(folder, List.of()))
                        .subfolders(subfoldersByFolder.getOrDefault(folder, List.of()).stream()
                                .map(subfolder -> skipPassThroughFolders(subfolder, filesByFolder, subfoldersByFolder))
                                .toList())
                        .build())
                .toList();
    }

    private static boolean isPassThrough(final String folder, final Map<String, List<InformationChunk>> filesByFolder,
                                         final Map<String, List<String>> subfoldersByFolder) {
        return !folder.equals(ROOT)
                && !filesByFolder.containsKey(folder)
                && subfoldersByFolder.getOrDefault(folder, List.of()).size() == 1;
    }

    private static String skipPassThroughFolders(final String folder, final Map<String, List<InformationChunk>> filesByFolder,
                                                 final Map<String, List<String>> subfoldersByFolder) {
        String current = folder;

        while (isPassThrough(current, filesByFolder, subfoldersByFolder)) {
            current = subfoldersByFolder.get(current).get(0);
        }
        return current;
    }

    private static Stream<String> getPathAndAncestors(final String folder) {
        return Stream.iterate(folder, path -> !path.equals(ROOT), ProjectTreeUtils::getParent);
    }

    private static String getParent(final String path) {
        return path.contains(PATH_SEPARATOR) ? StringUtils.substringBeforeLast(path, PATH_SEPARATOR) : ROOT;
    }

    private static int getDepth(final String folder) {
        return folder.equals(ROOT) ? 0 : StringUtils.countMatches(folder, PATH_SEPARATOR) + 1;
    }
}
