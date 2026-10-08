package com.mtcc.oracle.agent.tool;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.oracle.config.AgentProperties;
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

    private final RagService ragService;
    private final AgentProperties agentProperties;
    private final String question;
    private final Consumer<List<InformationChunk>> sourcesListener;
    private final AtomicInteger weakSearches = new AtomicInteger();

    @Autowired
    public AgentRagTools(final RagService ragService, final AgentProperties agentProperties) {
        this(ragService, agentProperties, null, sources -> { });
    }

    private AgentRagTools(final RagService ragService, final AgentProperties agentProperties, final String question,
                          final Consumer<List<InformationChunk>> sourcesListener) {
        this.ragService = ragService;
        this.agentProperties = agentProperties;
        this.question = question;
        this.sourcesListener = sourcesListener;
    }

    public AgentRagTools withQuestion(final String question) {
        return new AgentRagTools(ragService, agentProperties, question, sourcesListener);
    }

    public AgentRagTools withSourcesListener(final Consumer<List<InformationChunk>> sourcesListener) {
        return new AgentRagTools(ragService, agentProperties, question, sourcesListener);
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
            Searches the knowledge base (source code, documentation, configuration and project overviews) and returns
            the most relevant excerpts, each with its project, file and context.""")
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
