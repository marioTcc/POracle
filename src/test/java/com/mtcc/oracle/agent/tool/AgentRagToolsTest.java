package com.mtcc.oracle.agent.tool;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.entity.RetrievedChunk;
import com.mtcc.oracle.config.AgentProperties;
import com.mtcc.oracle.entity.ProjectFile;
import com.mtcc.oracle.exception.ProjectFileNotFoundException;
import com.mtcc.oracle.service.ProjectFileService;
import com.mtcc.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AgentRagToolsTest {

    @Mock
    private RagService ragService;
    @Mock
    private ProjectFileService projectFileService;
    @Mock
    private AgentProperties agentProperties;
    private AgentRagTools agentRagTools;

    private static final String SAMPLE_QUERY = "openai api client";

    @BeforeEach
    void setUp() {
        agentRagTools = new AgentRagTools(ragService, projectFileService, agentProperties);
        when(agentProperties.getSearchMaxChunkChars()).thenReturn(1500);
        when(agentProperties.getFileReadMaxChars()).thenReturn(40);
        when(ragService.countChunksByProject())
                .thenReturn(Mono.just(new TreeMap<>(Map.of("spring-ai", 120L, "spring-security", 300L))));
        when(ragService.searchChunks(anyString(), any(), any())).thenReturn(Mono.just(List.of()));
    }

    private RetrievedChunk getSampleChunk(final String text) {
        return RetrievedChunk.builder()
                .chunk(InformationChunk.buildChunk("spring-ai", "models/openai/OpenAiApi.java", "JAVA",
                        InformationType.CODE, text, "org.example.OpenAiApi", "code"))
                .score(0.8312)
                .build();
    }

    @Test
    void ragSearch_ShouldReturnEachResultWithItsProjectFileAndContext() {
        when(ragService.searchChunks(SAMPLE_QUERY, null, null)).thenReturn(Mono.just(List.of(getSampleChunk("void call() {}"))));

        final String result = agentRagTools.ragSearch(SAMPLE_QUERY, null).block();

        assertEquals("[1] project: spring-ai | file: models/openai/OpenAiApi.java | type: CODE"
                + " | context: org.example.OpenAiApi | score: 0.831\nvoid call() {}", result);
    }

    @Test
    void withSourcesListener_ShouldReportTheChunksEachToolReturned() {
        final RetrievedChunk retrieved = getSampleChunk("void call() {}");
        final InformationChunk summary = InformationChunk.buildChunk("spring-ai", "README.md", "TEXT",
                InformationType.PROJECT_SUMMARY, "Overview of project spring-ai.", "project overview", "llm-summary");
        when(ragService.searchChunks(SAMPLE_QUERY, null, null)).thenReturn(Mono.just(List.of(retrieved)));
        when(ragService.findChunksByType(eq(InformationType.PROJECT_SUMMARY), anyInt())).thenReturn(Mono.just(List.of(summary)));
        final List<InformationChunk> sources = new ArrayList<>();
        final AgentRagTools listenedTools = agentRagTools.withSourcesListener(sources::addAll);

        listenedTools.ragSearch(SAMPLE_QUERY, null).block();
        listenedTools.listProjects().block();
        agentRagTools.ragSearch(SAMPLE_QUERY, null).block();

        assertEquals(List.of(retrieved.getChunk(), summary), sources);
    }

    @Test
    void withQuestion_ShouldSearchWithTheQueryAndRankAgainstTheQuestion() {
        agentRagTools.withQuestion("How do I call the OpenAI API?").ragSearch(SAMPLE_QUERY, null).block();

        verify(ragService).searchChunks(SAMPLE_QUERY, null, "How do I call the OpenAI API?");
    }

    @Test
    void ragSearch_ShouldAdviseToSearchAgainThenToStop_WhenTheBestScoreIsWeak() {
        when(agentProperties.getSearchWeakScore()).thenReturn(3.0);
        when(ragService.searchChunks(SAMPLE_QUERY, null, null)).thenReturn(Mono.just(List.of(getSampleChunk("void call() {}"))));
        final AgentRagTools requestTools = agentRagTools.withQuestion(null);

        final String firstResult = requestTools.ragSearch(SAMPLE_QUERY, null).block();
        final String secondResult = requestTools.ragSearch(SAMPLE_QUERY, null).block();
        final String thirdResult = requestTools.ragSearch(SAMPLE_QUERY, null).block();

        assertTrue(firstResult.startsWith("[1] project: spring-ai"), firstResult);
        assertTrue(firstResult.endsWith("or without the 'project' argument."), firstResult);
        assertTrue(secondResult.contains("Call 'ragSearch' again with other words"), secondResult);
        assertTrue(thirdResult.contains("Do not search again."), thirdResult);
        assertTrue(agentRagTools.withQuestion(null).ragSearch(SAMPLE_QUERY, null).block().contains("Call 'ragSearch' again"),
                "Should count the weak searches of each request apart");
    }

    @Test
    void ragSearch_ShouldNotAdviseAnything_WhenTheBestScoreIsGoodOrNoWeakScoreIsSet() {
        when(ragService.searchChunks(SAMPLE_QUERY, null, null)).thenReturn(Mono.just(List.of(getSampleChunk("void call() {}"))));

        when(agentProperties.getSearchWeakScore()).thenReturn(0.5);
        assertTrue(agentRagTools.ragSearch(SAMPLE_QUERY, null).block().endsWith("void call() {}"));

        when(agentProperties.getSearchWeakScore()).thenReturn(null);
        assertTrue(agentRagTools.ragSearch(SAMPLE_QUERY, null).block().endsWith("void call() {}"));
    }

    @Test
    void ragSearch_ShouldCutLongExcerpts() {
        when(agentProperties.getSearchMaxChunkChars()).thenReturn(20);
        when(ragService.searchChunks(SAMPLE_QUERY, null, null))
                .thenReturn(Mono.just(List.of(getSampleChunk("void call() { return client.post(); }"))));

        final String result = agentRagTools.ragSearch(SAMPLE_QUERY, null).block();

        assertNotNull(result);
        assertTrue(result.endsWith("\nvoid call() { ret..."), result);
    }

    @Test
    void ragSearch_ShouldFilterByTheKnownProjectMatchingTheRequestedName() {
        final String result = agentRagTools.ragSearch("authentication", "Spring AI").block();

        assertNotNull(result);
        assertTrue(result.contains("No relevant content found in project 'spring-ai'"));

        verify(ragService).searchChunks("authentication", "spring-ai", null);
    }

    @Test
    void ragSearch_ShouldListKnownProjects_WhenTheRequestedProjectIsUnknown() {
        final String result = agentRagTools.ragSearch("authentication", "spring-boot").block();

        assertEquals("Unknown project 'spring-boot'. Known projects: spring-ai, spring-security", result);
    }

    @Test
    void ragSearch_ShouldReportAFailedSearchInsteadOfFailing() {
        when(ragService.searchChunks(anyString(), any(), any())).thenReturn(Mono.error(new IllegalStateException("store not reachable")));

        final String result = agentRagTools.ragSearch("authentication", null).block();
        assertEquals("The search failed: store not reachable", result);
    }

    private ProjectFile fileOf(final String content) {
        return ProjectFile.builder().node(getSampleChunk("class OpenAiApi\nMethods: call").getChunk()).content(content).build();
    }

    @Test
    void readFile_ShouldReturnAShortFileWhole() {
        when(projectFileService.readFile("spring-ai", "models/openai/OpenAiApi.java"))
                .thenReturn(Mono.just(fileOf("class OpenAiApi {\n    void call() {}\n}\n")));

        final String result = agentRagTools.readFile(" spring-ai ", "models/openai/OpenAiApi.java", null).block();

        assertEquals("project: spring-ai | file: models/openai/OpenAiApi.java\nlines 1-3 of 3\n"
                + "class OpenAiApi {\n    void call() {}\n}", result);
    }

    @Test
    void readFile_ShouldReturnALongFileInParts_AndTellHowToReadOn() {
        when(projectFileService.readFile(anyString(), anyString()))
                .thenReturn(Mono.just(fileOf("first line of the file\nsecond line of the file\nthird line of the file\n")));

        final String firstPart = agentRagTools.readFile("spring-ai", "models/openai/OpenAiApi.java", null).block();
        final String lastPart = agentRagTools.readFile("spring-ai", "models/openai/OpenAiApi.java", 2).block();

        assertEquals("project: spring-ai | file: models/openai/OpenAiApi.java\nlines 1-1 of 3\nfirst line of the file\n\n---\n\n"
                + "NOTE: the file goes on. Call 'readFile' again with fromLine=2 only if the answer needs the rest of it.", firstPart);
        assertTrue(lastPart.startsWith("project: spring-ai | file: models/openai/OpenAiApi.java\nlines 2-2 of 3\nsecond line"), lastPart);
        assertTrue(lastPart.contains("fromLine=3"), lastPart);
    }

    @Test
    void readFile_ShouldReturnTheOverviewOfAFolder() {
        final InformationChunk folder = InformationChunk.buildChunk("spring-ai", "models/openai", "TEXT",
                InformationType.FOLDER_SUMMARY, "Contents of folder models/openai of project spring-ai:\n- models/openai/OpenAiApi.java",
                "folder models/openai", "llm-summary");
        when(projectFileService.readFile("spring-ai", "models/openai"))
                .thenReturn(Mono.just(ProjectFile.builder().node(folder).content(folder.getText()).folder(true).build()));

        final String result = agentRagTools.readFile("spring-ai", "models/openai", null).block();

        assertEquals("project: spring-ai | folder: models/openai\n" + folder.getText(), result);
    }

    @Test
    void readFile_ShouldReportTheFileItReturnedAsASource() {
        when(projectFileService.readFile(anyString(), anyString())).thenReturn(Mono.just(fileOf("void call() {}")));
        final List<InformationChunk> sources = new ArrayList<>();

        agentRagTools.withSourcesListener(sources::addAll).readFile("spring-ai", "models/openai/OpenAiApi.java", null).block();

        assertEquals(1, sources.size());
        assertEquals("models/openai/OpenAiApi.java", sources.get(0).getFilePath());
        assertEquals("lines 1-1 of 1\nvoid call() {}", sources.get(0).getText());
    }

    @Test
    void readFile_ShouldExplainTheFailureInsteadOfFailing() {
        when(projectFileService.readFile(anyString(), anyString())).thenReturn(Mono.error(
                new ProjectFileNotFoundException("'missing.java' is not a file or folder of project 'spring-ai'")));

        assertEquals("The file cannot be read: 'missing.java' is not a file or folder of project 'spring-ai'. "
                + "Use a project and a path given by 'ragSearch'.", agentRagTools.readFile("spring-ai", "missing.java", null).block());
    }

    @Test
    void listProjects_ShouldReturnEveryProjectWithItsOverview() {
        when(ragService.findChunksByType(eq(InformationType.PROJECT_SUMMARY), anyInt())).thenReturn(Mono.just(List.of(
                InformationChunk.buildChunk("spring-ai", "README.md", "TEXT", InformationType.PROJECT_SUMMARY,
                        "Overview of project spring-ai: AI framework.", "project overview", "llm-summary"))));

        final String result = agentRagTools.listProjects().block();

        assertEquals("""
                project: spring-ai | indexed chunks: 120
                Overview of project spring-ai: AI framework.
                
                ---
                
                project: spring-security | indexed chunks: 300
                No overview available.""", result);
    }
}
