package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Exceptions;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WholeFileChunkingServiceImplTest {

    @TempDir
    private Path tempDir;

    private final ChunkingProperties chunkingProperties = mock(ChunkingProperties.class);

    private final WholeFileChunkingServiceImpl wholeFileChunkingService =
            new WholeFileChunkingServiceImpl(new IOServiceImpl(null, null), chunkingProperties);

    @BeforeEach
    void setUp() {
        when(chunkingProperties.getFileOutlineMaxChars()).thenReturn(60);
    }

    private Project project() {
        return Project.builder().projectName("sample-project").projectPath(tempDir).build();
    }

    private List<InformationChunk> chunk(final Path file) {
        return wholeFileChunkingService.chunkFile(project(), file, InformationType.CODE)
                .collectList()
                .block();
    }

    @Test
    void chunkFile_ShouldEmitAShortFileWholeAsOneChunk() throws IOException {
        final String content = "fun main() {\n\n    println(\"hello\")\n}";
        final Path file = Files.writeString(Files.createDirectories(tempDir.resolve("src")).resolve("Main.kt"), content);

        final List<InformationChunk> chunks = chunk(file);

        assertEquals(1, chunks.size());
        assertEquals(content, chunks.get(0).getText());
        assertEquals("src/Main.kt", chunks.get(0).getFilePath());
        assertEquals("KT", chunks.get(0).getLanguage());
        assertEquals(InformationType.CODE, chunks.get(0).getInformationType());
        assertEquals("file-head", chunks.get(0).getChunkingStrategy());
    }

    @Test
    void chunkFile_ShouldKeepOnlyTheFirstLinesOfALongFile() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("build.sh"),
                "#!/bin/sh\n# Builds the project.\n" + "echo building the project step by step\n".repeat(5));

        assertEquals("#!/bin/sh\n# Builds the project.\n...", chunk(file).get(0).getText());
    }

    @Test
    void chunkFile_ShouldReadFilesThatAreNotUtf8() throws IOException {
        final String content = "mensagem=Não foi possível autenticar";
        final Path file = Files.write(tempDir.resolve("messages_pt_PT.properties"), content.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(content, chunk(file).get(0).getText());
    }

    @Test
    void chunkFile_ShouldFail_WhenTheFileCannotBeRead() {
        final RuntimeException error = assertThrows(RuntimeException.class, () -> chunk(tempDir.resolve("missing.kt")));

        assertInstanceOf(NoSuchFileException.class, Exceptions.unwrap(error));
    }
}
