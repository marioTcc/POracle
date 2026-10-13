package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.config.SectionParserConfig;
import com.mtcc.project.extractor.entity.Project;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TextChunkingServiceImplTest {

    private static final String LONG_SENTENCE = "This sentence is repeated to make the section longer than the limit. ";

    @TempDir
    private Path tempDir;

    @Mock
    private ChunkingProperties chunkingProperties;

    private TextChunkingServiceImpl textChunkingService;

    @BeforeEach
    void setUp() {
        textChunkingService = new TextChunkingServiceImpl(chunkingProperties, new IOServiceImpl(null, null),
                new SectionParserConfig().sectionParsers());
        when(chunkingProperties.getFileOutlineMaxChars()).thenReturn(200);
    }

    private Project project() {
        return Project.builder().projectName("docs-project").projectPath(tempDir).build();
    }

    private List<InformationChunk> chunk(final String fileName, final String content) throws IOException {
        final Path file = Files.writeString(tempDir.resolve(fileName), content, StandardCharsets.UTF_8);

        return textChunkingService.chunkFile(project(), file, InformationType.DOC)
                .collectList()
                .block();
    }

    private String outline(final String fileName, final String content) throws IOException {
        final List<InformationChunk> chunks = chunk(fileName, content);

        assertEquals(1, chunks.size(), "A file must be outlined as one chunk");
        return chunks.get(0).getText();
    }

    @Test
    void chunkFile_ShouldKeepAFileSmallerThanTheLimitWhole() throws IOException {
        assertEquals("# Getting started\n\nFirst paragraph of the guide.\n\n## Install\n\nRun the installer.", outline("guide.md", """
                # Getting started

                First paragraph of the guide.

                ## Install

                Run the installer.
                """));
    }

    @Test
    void chunkFile_ShouldOutlineALongMarkdownFileWithItsFirstLinesAndItsHeadings() throws IOException {
        final String outline = outline("guide.md", """
                # Guide

                Short introduction of the guide.

                ## Install

                %s

                ### Linux

                %s

                ## Usage

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)));

        assertEquals("""
                # Guide

                Short introduction of the guide.

                ## Install
                ...

                Sections:
                Guide
                  Install
                    Linux
                  Usage""", outline);
    }

    @Test
    void chunkFile_ShouldIgnoreHeadingsInsideCodeBlocks() throws IOException {
        final String outline = outline("guide.md", """
                # Guide

                %s

                ```bash
                # not a heading
                ./mvnw package
                ```

                ## Run

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)));

        assertTrue(outline.endsWith("Sections:\nGuide\n  Run"), outline);
    }

    @Test
    void chunkFile_ShouldOutlineAsciiDocAndRestructuredTextHeadings() throws IOException {
        assertTrue(outline("index.adoc", """
                = Reference

                == Authentication

                %s

                == Authorization

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)))
                .endsWith("Sections:\nReference\n  Authentication\n  Authorization"));

        assertTrue(outline("index.rst", """
                Reference
                =========

                Install
                -------

                %s

                Usage
                -----

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)))
                .endsWith("Sections:\nReference\n  Install\n  Usage"));
    }

    @Test
    void chunkFile_ShouldKeepOnlyTheFirstLines_WhenALongFileHasNoSections() throws IOException {
        final String outline = outline("notes.txt", (LONG_SENTENCE.strip() + "\n").repeat(6));

        assertEquals((LONG_SENTENCE.strip() + "\n").repeat(2) + "...", outline);
    }

    @Test
    void chunkFile_ShouldOutlineYamlWithItsKeys() throws IOException {
        final String outline = outline("application.yml", """
                # Server settings
                server:
                  port: 8080

                spring:
                  datasource:
                    url: jdbc:postgresql://localhost:5432/orders
                    username: orders
                    driver-class-name: org.postgresql.Driver
                  jpa:
                    hibernate:
                      ddl-auto: validate
                    properties:
                      hibernate.dialect: org.hibernate.dialect.PostgreSQLDialect
                      hibernate.format_sql: true
                """);

        assertTrue(outline.startsWith("# Server settings\nserver:\n  port: 8080"), outline);
        assertTrue(outline.contains("Sections:\nserver\n  port\nspring\n  datasource\n    url"), outline);
    }

    @Test
    void chunkFile_ShouldOutlinePropertiesJsonAndXmlWithTheirKeys() throws IOException {
        assertTrue(outline("application.properties", """
                spring.datasource.url=jdbc:postgresql://localhost:5432/orders
                spring.datasource.username=orders
                spring.jpa.hibernate.ddl-auto=validate
                logging.level.root=INFO
                logging.pattern.console=%d{HH:mm:ss} %-5level %msg%n
                server.port=8080
                """).contains("Sections:\nspring\n"));

        assertTrue(outline("package.json", """
                {"name": "orders-ui", "version": "1.0.0",
                 "scripts": {"build": "vite build", "test": "vitest run", "lint": "eslint src --max-warnings 0",
                             "preview": "vite preview --host 0.0.0.0 --port 4173", "format": "prettier --write src"},
                 "dependencies": {"react": "18.2.0", "react-dom": "18.2.0", "react-router-dom": "6.22.0"}}
                """).contains("scripts"));

        final String pom = outline("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>orders</artifactId>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-webflux</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);
        assertTrue(pom.contains("Sections:\n"), pom);
        assertTrue(pom.contains("dependencies\n  dependency"), pom);
    }

    @Test
    void chunkFile_ShouldKeepTheFirstLines_WhenTheStructureCannotBeRead() throws IOException {
        final String outline = outline("broken.json", "{\"name\": \"orders-ui\",\n\n" + LONG_SENTENCE.repeat(4));

        assertEquals("{\"name\": \"orders-ui\",\n...", outline);
    }

    @Test
    void chunkFile_ShouldEmitNothing_WhenTheFileIsShorterThanTenCharacters() throws IOException {
        assertTrue(chunk("notes.txt", "short\n").isEmpty());
    }

    @Test
    void chunkFile_ShouldDescribeTheOutline() throws IOException {
        final InformationChunk chunk = chunk("guide.md", "A paragraph long enough to be kept.").get(0);

        assertEquals("docs-project", chunk.getProjectName());
        assertEquals("guide.md", chunk.getFilePath());
        assertEquals("guide.md", chunk.getContext());
        assertEquals("MD", chunk.getLanguage());
        assertEquals(InformationType.DOC, chunk.getInformationType());
        assertEquals("text-outline", chunk.getChunkingStrategy());
    }

    @Test
    void chunkFile_ShouldFail_WhenTheFileCannotBeRead() {
        final RuntimeException error = assertThrows(RuntimeException.class, () ->
                textChunkingService.chunkFile(project(), tempDir.resolve("missing.md"), InformationType.DOC)
                        .collectList()
                        .block());

        assertInstanceOf(NoSuchFileException.class, reactor.core.Exceptions.unwrap(error));
    }
}
