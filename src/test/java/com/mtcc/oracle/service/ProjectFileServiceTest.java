package com.mtcc.oracle.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.security.SecretRedactor;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IRepositoryFileServiceImpl;
import com.mtcc.oracle.config.AgentProperties;
import com.mtcc.oracle.entity.ProjectFile;
import com.mtcc.oracle.exception.ProjectFileNotFoundException;
import com.mtcc.rag.service.RagService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProjectFileServiceTest {

    private static final String PROJECT = "spring-ai";
    private static final String FILE_PATH = "models/openai/OpenAiApi.java";
    private static final String LOCAL_PATH = "/projects/spring-ai/models/openai/OpenAiApi.java";
    private static final String REPOSITORY_URL = "https://github.com/spring-projects/spring-ai.git";
    private static final String LOCAL_READ = "local read";
    private static final String REPOSITORY_READ = "repository read";

    @Mock
    private RagService ragService;
    @Mock
    private IIOServiceImpl ioService;
    @Mock
    private IRepositoryFileServiceImpl repositoryFileService;
    @Mock
    private AgentProperties agentProperties;

    private ProjectFileService projectFileService;

    private final List<String> events = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        projectFileService = new ProjectFileService(ragService, ioService, repositoryFileService, new SecretRedactor(), agentProperties);

        when(agentProperties.getSecretsRedactionEnabled()).thenReturn(true);
        when(ragService.findChunksByPath(anyString(), anyString())).thenReturn(Mono.just(List.of()));
        when(ioService.readText(any())).thenReturn(Mono.fromCallable(() -> {
            events.add(LOCAL_READ);
            return "local content";
        }));
        when(repositoryFileService.readFile(anyString(), any(), anyString())).thenReturn(Mono.fromCallable(() -> {
            events.add(REPOSITORY_READ);
            return "repository content";
        }));
    }

    private InformationChunk node(final InformationType type, final String filePath, final String localPath, final String repositoryUrl) {
        return InformationChunk.buildChunk(PROJECT, filePath, "java", type, "outline", "", "code-outline").toBuilder()
                .localPath(localPath)
                .repositoryUrl(repositoryUrl)
                .revision("4f2c9d1")
                .build();
    }

    private void indexed(final InformationChunk... chunks) {
        when(ragService.findChunksByPath(PROJECT, chunks[0].getFilePath())).thenReturn(Mono.just(List.of(chunks)));
    }

    @Test
    void readFile_ShouldReadTheLocalFile_WhenItIsStillThere() {
        indexed(node(InformationType.CODE, FILE_PATH, LOCAL_PATH, REPOSITORY_URL));

        final ProjectFile file = projectFileService.readFile(PROJECT, FILE_PATH).block();

        assertEquals("local content", file.getContent());
        assertEquals(FILE_PATH, file.getNode().getFilePath());
        assertFalse(file.isFolder());
        assertEquals(List.of(LOCAL_READ), events);
    }

    @Test
    void readFile_ShouldReadTheRepository_WhenTheLocalFileCannotBeRead() {
        indexed(node(InformationType.CODE, FILE_PATH, LOCAL_PATH, REPOSITORY_URL));
        when(ioService.readText(Path.of(LOCAL_PATH))).thenReturn(Mono.error(new IOException("deleted")));
        when(repositoryFileService.readFile(REPOSITORY_URL, "4f2c9d1", FILE_PATH)).thenReturn(Mono.fromCallable(() -> {
            events.add(REPOSITORY_READ);
            return "content at the revision";
        }));

        assertEquals("content at the revision", projectFileService.readFile(PROJECT, FILE_PATH).block().getContent());
        assertEquals(List.of(REPOSITORY_READ), events);
    }

    @Test
    void readFile_ShouldReadTheRepository_WhenTheFileHasNoLocalPath() {
        indexed(node(InformationType.CODE, FILE_PATH, "", REPOSITORY_URL));

        assertEquals("repository content", projectFileService.readFile(PROJECT, FILE_PATH).block().getContent());
        assertEquals(List.of(REPOSITORY_READ), events);
    }

    @Test
    void readFile_ShouldFail_WhenTheFileIsNeitherLocalNorInARepository() {
        indexed(node(InformationType.CODE, FILE_PATH, LOCAL_PATH, ""));
        when(ioService.readText(any())).thenReturn(Mono.error(new IOException("deleted")));

        final ProjectFileNotFoundException error = assertThrows(ProjectFileNotFoundException.class,
                () -> projectFileService.readFile(PROJECT, FILE_PATH).block());

        assertEquals("The content of '" + FILE_PATH + "' is no longer available", error.getMessage());
        assertTrue(events.isEmpty());
    }

    @Test
    void readFile_ShouldFailWithoutReadingAnything_WhenThePathIsNotInTheIndex() {
        final ProjectFileNotFoundException error = assertThrows(ProjectFileNotFoundException.class,
                () -> projectFileService.readFile(PROJECT, "../../etc/passwd").block());

        assertEquals("'../../etc/passwd' is not a file or folder of project 'spring-ai'", error.getMessage());
        assertTrue(events.isEmpty());
    }

    @Test
    void readFile_ShouldReturnTheOverviewOfAFolder_WithoutReadingAnyFile() {
        final InformationChunk folder = node(InformationType.FOLDER_SUMMARY, "models/openai", "", REPOSITORY_URL);
        indexed(folder);

        final ProjectFile file = projectFileService.readFile(PROJECT, "\\models\\openai/").block();

        assertTrue(file.isFolder());
        assertEquals("outline", file.getContent());
        assertTrue(events.isEmpty());
    }

    @Test
    void readFile_ShouldReadTheFile_NotTheSummaryWrittenFromIt() {
        indexed(node(InformationType.MODULE_SUMMARY, "models/openai/README.md", "", ""),
                node(InformationType.DOC, "models/openai/README.md", LOCAL_PATH, ""));

        assertEquals(InformationType.DOC, projectFileService.readFile(PROJECT, "models/openai/README.md").block()
                .getNode().getInformationType());
    }

    @Test
    void readFile_ShouldRedactTheSecretsOfTheFile() {
        indexed(node(InformationType.CONFIG, "application.properties", LOCAL_PATH, ""));
        when(ioService.readText(any())).thenReturn(Mono.just("db.password=s3cr3t"));

        assertEquals("db.password=[REDACTED]", projectFileService.readFile(PROJECT, "application.properties").block().getContent());

        when(agentProperties.getSecretsRedactionEnabled()).thenReturn(false);
        assertEquals("db.password=s3cr3t", projectFileService.readFile(PROJECT, "application.properties").block().getContent());
    }
}
