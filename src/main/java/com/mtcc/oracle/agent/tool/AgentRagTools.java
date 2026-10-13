package com.mtcc.oracle.agent.tool;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.oracle.config.AgentProperties;
import com.mtcc.oracle.entity.ProjectFile;
import com.mtcc.oracle.service.ProjectFileService;
import com.mtcc.rag.service.RagService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
public class AgentRagTools {

    private static final String RESULTS_SEPARATOR = "\n\n---\n\n";
    private static final Set<String> NO_PROJECT_VALUES = Set.of("", "null", "none", "all", "any");
    private static final int MAX_PROJECT_SUMMARIES = 100;
    private static final int MAX_SEARCH_AGAIN_ADVICES = 2;
    private static final String SEARCH_AGAIN_ADVICE = """
            NOTE: these excerpts match the search poorly. Call 'ragSearch' again with other words: the technical \
            terms a developer would use for this (names of the feature, classes, annotations or modules), \
            or without the 'project' argument.""";
    private static final String STOP_SEARCHING_ADVICE = """
            NOTE: these excerpts match the search poorly, like the earlier ones. Do not search again. Unless an \
            excerpt clearly answers the question, say that the knowledge base does not contain the answer.""";

    private static final String READ_ON_ADVICE = """
            NOTE: the file goes on. Call 'readFile' again with fromLine=%d only if the answer needs the rest of it.""";

    private final RagService ragService;
    private final ProjectFileService projectFileService;
    private final AgentProperties agentProperties;
    private final String question;
    private final Consumer<List<InformationChunk>> sourcesListener;
    private final AtomicInteger weakSearches = new AtomicInteger();

    @Autowired
    public AgentRagTools(final RagService ragService, final ProjectFileService projectFileService,
                         final AgentProperties agentProperties) {
        this(ragService, projectFileService, agentProperties, null, sources -> { });
    }

    private AgentRagTools(final RagService ragService, final ProjectFileService projectFileService,
                          final AgentProperties agentProperties, final String question,
                          final Consumer<List<InformationChunk>> sourcesListener) {
        this.ragService = ragService;
        this.projectFileService = projectFileService;
        this.agentProperties = agentProperties;
        this.question = question;
        this.sourcesListener = sourcesListener;
    }

    public AgentRagTools withQuestion(final String question) {
        return new AgentRagTools(ragService, projectFileService, agentProperties, question, sourcesListener);
    }

    public AgentRagTools withSourcesListener(final Consumer<List<InformationChunk>> sourcesListener) {
        return new AgentRagTools(ragService, projectFileService, agentProperties, question, sourcesListener);
    }

    @Tool("""
            Lists the projects stored in the knowledge base, with their exact names and a short overview of each.
            Use it when the user asks which projects are available, or when you need the exact name of a project.""")
    public Mono<String> listProjects() {
        log.info("Tool {} called", "listProjects");

        return ragService.countChunksByProject()
                .zipWith(ragService.findChunksByType(InformationType.PROJECT_SUMMARY, MAX_PROJECT_SUMMARIES))
                .doOnNext(projectsAndSummaries -> sourcesListener.accept(projectsAndSummaries.getT2()))
                .map(projectsAndSummaries -> {
                    final Map<String, String> summaryByProject = projectsAndSummaries.getT2().stream()
                            .collect(Collectors.toMap(
                                    InformationChunk::getProjectName,
                                    InformationChunk::getText,
                                    (first, second) -> first));

                    return projectsAndSummaries.getT1().entrySet().stream()
                            .map(project -> "project: " + project.getKey()
                                    + " | indexed chunks: " + project.getValue()
                                    + "\n" + summaryByProject.getOrDefault(project.getKey(), "No overview available."))
                            .collect(Collectors.joining(RESULTS_SEPARATOR));
                })
                .map(projects -> projects.isEmpty() ? "The knowledge base does not contain any project." : projects)
                .doOnNext(projects -> log.debug("Tool {} result: {}", "listProjects", projects))
                .onErrorResume(error -> {
                    log.error("Tool {} failed", "listProjects", error);
                    return Mono.just("The list of projects is not available: " + error.getMessage());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Tool("""
            Searches the knowledge base (outlines of the source, documentation and configuration files, overviews of the
            folders and of the projects) and returns the most relevant ones, each with its project and file path.
            Use 'readFile' to read the content of a file it returns.""")
    public Mono<String> ragSearch(
            @P("What to look for, as a specific search query in English, with the technical terms a developer "
                    + "would use for it") final String query,
            @P(value = "Exact project name to restrict the search to, as returned by listProjects. "
                    + "Omit it to search every project.", required = false) final String project) {
        final String requestedProject = StringUtils.trimToEmpty(project);
        log.info("Tool {} called with query: {} and project: {}", "ragSearch", query, requestedProject);

        if (NO_PROJECT_VALUES.contains(normalizeProjectName(requestedProject))) {
            return search(query, null);
        }

        return ragService.countChunksByProject()
                .map(Map::keySet)
                .onErrorReturn(Set.of())
                .flatMap(knownProjects -> knownProjects.isEmpty() ?
                        search(query, requestedProject) :
                        knownProjects.stream()
                                .filter(known -> normalizeProjectName(known).equals(normalizeProjectName(requestedProject)))
                                .findFirst()
                                .map(known -> search(query, known))
                                .orElseGet(() -> Mono.just("Unknown project '" + requestedProject
                                        + "'. Known projects: " + String.join(", ", knownProjects))));
    }

    @Tool("""
            Reads a file of a project and returns its content, or a part of it when the file is long.
            With the path of a folder, returns the overview of the folder and the list of what it contains.""")
    public Mono<String> readFile(
            @P("Exact project name, as given in the results of ragSearch or listProjects") final String project,
            @P("Path of the file or folder inside the project, exactly as given in the results of ragSearch") final String path,
            @P(value = "Line to start reading from, to continue a long file. Omit it to read from the beginning.",
                    required = false) final Integer fromLine) {
        final String projectName = StringUtils.trimToEmpty(project);
        log.info("Tool {} called with project: {}, path: {} and line: {}", "readFile", projectName, path, fromLine);

        return projectFileService.readFile(projectName, path)
                .map(file -> file.isFolder() ? file : getExcerpt(file, fromLine == null ? 1 : fromLine))
                .doOnNext(file -> sourcesListener.accept(List.of(file.getNode().toBuilder().text(file.getContent()).build())))
                .map(this::formatFile)
                .doOnNext(result -> log.debug("Tool {} result: {}", "readFile", result))
                .onErrorResume(error -> {
                    log.warn("Tool {} failed: {}", "readFile", error.toString());
                    return Mono.just("The file cannot be read: " + error.getMessage()
                            + ". Use a project and a path given by 'ragSearch'.");
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ProjectFile getExcerpt(final ProjectFile file, final int fromLine) {
        final int maxChars = agentProperties.getFileReadMaxChars();
        final List<String> lines = file.getContent().lines().toList();
        final int firstLine = Math.min(Math.max(fromLine, 1), Math.max(lines.size(), 1));
        final StringBuilder excerpt = new StringBuilder();
        int lastLine = firstLine - 1;

        while (lastLine < lines.size()
                && (excerpt.isEmpty() || excerpt.length() + lines.get(lastLine).length() < maxChars)) {
            excerpt.append(lines.get(lastLine++)).append("\n");
        }

        return ProjectFile.builder()
                .node(file.getNode())
                .content("lines " + firstLine + "-" + lastLine + " of " + lines.size() + "\n"
                        + StringUtils.left(excerpt.toString(), maxChars).stripTrailing()
                        + (lastLine < lines.size() ? RESULTS_SEPARATOR + READ_ON_ADVICE.formatted(lastLine + 1) : ""))
                .build();
    }

    private String formatFile(final ProjectFile file) {
        final InformationChunk node = file.getNode();
        final StringBuilder result = new StringBuilder("project: ").append(node.getProjectName());

        appendIfPresent(result, file.isFolder() ? "folder" : "file", node.getFilePath());
        return result.append("\n").append(file.getContent()).toString();
    }

    private Mono<String> search(final String query, final String projectName) {
        return ragService.searchChunks(query, projectName, question)
                .doOnNext(chunks -> log.info("Tool {} found {} results, best score: {}", "ragSearch", chunks.size(),
                        chunks.isEmpty() ? "n/a" : chunks.get(0).getScore()))
                .doOnNext(chunks -> sourcesListener.accept(chunks.stream().map(RetrievedChunk::getChunk).toList()))
                .map(chunks -> chunks.isEmpty() ?
                        getNoResultsMessage(projectName) : formatResults(chunks) + getWeakMatchAdvice(chunks))
                .doOnNext(results -> log.debug("Tool {} result: {}", "ragSearch", results))
                .onErrorResume(error -> {
                    log.error("Tool {} failed", "ragSearch", error);
                    return Mono.just("The search failed: " + error.getMessage());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private String formatResults(final List<RetrievedChunk> chunks) {
        return IntStream.range(0, chunks.size())
                .mapToObj(position -> formatResult(position + 1, chunks.get(position)))
                .collect(Collectors.joining(RESULTS_SEPARATOR));
    }

    private String getWeakMatchAdvice(final List<RetrievedChunk> chunks) {
        final Double weakScore = agentProperties.getSearchWeakScore();

        if (weakScore == null || chunks.get(0).getScore() >= weakScore) {
            return "";
        }
        return RESULTS_SEPARATOR
                + (weakSearches.incrementAndGet() <= MAX_SEARCH_AGAIN_ADVICES ? SEARCH_AGAIN_ADVICE : STOP_SEARCHING_ADVICE);
    }

    private String formatResult(final int position, final RetrievedChunk retrievedChunk) {
        final InformationChunk chunk = retrievedChunk.getChunk();
        final StringBuilder result = new StringBuilder("[").append(position).append("]")
                .append(" project: ").append(chunk.getProjectName());

        appendIfPresent(result, "file", chunk.getFilePath());
        appendIfPresent(result, "type", chunk.getInformationType());
        appendIfPresent(result, "context", chunk.getContext());
        result.append(" | score: ").append(String.format(Locale.ROOT, "%.3f", retrievedChunk.getScore()));

        return result.append("\n")
                .append(StringUtils.abbreviate(chunk.getText(), agentProperties.getSearchMaxChunkChars()))
                .toString();
    }

    private void appendIfPresent(final StringBuilder result, final String label, final Object value) {
        if (value != null && StringUtils.isNotBlank(value.toString())) {
            result.append(" | ").append(label).append(": ").append(value);
        }
    }

    private String normalizeProjectName(final String project) {
        return StringUtils.trimToEmpty(project).toLowerCase(Locale.ROOT).replaceAll("[\\s_]+", "-");
    }

    private String getNoResultsMessage(final String projectName) {
        return projectName == null ?
                "No relevant content found in the knowledge base for this query." :
                "No relevant content found in project '" + projectName + "'. "
                        + "Search again with a different query, or without the project.";
    }
}
