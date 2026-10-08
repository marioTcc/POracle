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
        when(chunkingProperties.getTextChunkMaxChars()).thenReturn(200);
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

    private List<String> texts(final List<InformationChunk> chunks) {
        return chunks.stream().map(InformationChunk::getText).toList();
    }

    private List<String> contexts(final List<InformationChunk> chunks) {
        return chunks.stream().map(InformationChunk::getContext).toList();
    }

    @Test
    void chunkFile_ShouldKeepAFileSmallerThanTheLimitWhole() throws IOException {
        final List<InformationChunk> chunks = chunk("guide.md", """
                # Getting started

                First paragraph of the guide.

                ## Install

                Run the installer.
                """);

        assertEquals(List.of("# Getting started\n\nFirst paragraph of the guide.\n\n## Install\n\nRun the installer."), texts(chunks));
        assertEquals(List.of("guide.md"), contexts(chunks));
    }

    @Test
    void chunkFile_ShouldSplitMarkdownOnHeadingsAndUseTheHeadingPathAsContext() throws IOException {
        final List<InformationChunk> chunks = chunk("guide.md", """
                # Guide

                Short introduction of the guide.

                ## Install

                %s

                ### Linux

                %s

                ## Usage

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)));

        assertEquals(List.of("Guide", "Guide > Install", "Guide > Install > Linux", "Guide > Usage"), contexts(chunks));
        assertEquals("# Guide\n\nShort introduction of the guide.", chunks.get(0).getText());
        assertTrue(chunks.get(1).getText().startsWith("## Install\n\nThis sentence"));
        assertTrue(chunks.get(2).getText().startsWith("### Linux\n\nThis sentence"));
        assertTrue(chunks.get(3).getText().startsWith("## Usage\n\nThis sentence"));
    }

    @Test
    void chunkFile_ShouldPackSmallSiblingSectionsTogether() throws IOException {
        final List<InformationChunk> chunks = chunk("faq.md", """
                # FAQ

                ## First

                First short answer of the list.

                ## Second

                Second short answer of the list.

                ## Third

                %s
                """.formatted(LONG_SENTENCE.repeat(2)));

        assertEquals(List.of("## First\n\nFirst short answer of the list.\n\n## Second\n\nSecond short answer of the list.",
                "## Third\n\n" + LONG_SENTENCE.repeat(2).strip()), texts(chunks));
        assertEquals(List.of("FAQ", "FAQ > Third"), contexts(chunks));
    }

    @Test
    void chunkFile_ShouldSplitALongSectionByParagraphs_AndKeepCodeBlocksWhole() throws IOException {
        final List<InformationChunk> chunks = chunk("guide.md", """
                # Guide

                %s

                ```bash
                # not a heading
                ./mvnw package

                ./mvnw spring-boot:run
                ```

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)));

        assertEquals(3, chunks.size());
        assertEquals("```bash\n# not a heading\n./mvnw package\n\n./mvnw spring-boot:run\n```", chunks.get(1).getText());
        assertEquals(List.of("Guide", "Guide", "Guide"), contexts(chunks));
    }

    @Test
    void chunkFile_ShouldSplitAsciiDocOnHeadings() throws IOException {
        final List<InformationChunk> chunks = chunk("index.adoc", """
                = Reference

                == Authentication

                %s

                ----
                == not a heading
                ----

                == Authorization

                %s
                """.formatted((LONG_SENTENCE.strip() + "\n").repeat(3).strip(), LONG_SENTENCE.repeat(2)));

        assertEquals(List.of("Reference > Authentication", "Reference > Authentication", "Reference > Authentication",
                "Reference > Authorization"), contexts(chunks));
        assertTrue(chunks.get(0).getText().startsWith("== Authentication\n\nThis sentence"));
        assertEquals("----\n== not a heading\n----", chunks.get(2).getText());
    }

    @Test
    void chunkFile_ShouldSplitRestructuredTextOnUnderlinedTitles() throws IOException {
        final List<InformationChunk> chunks = chunk("index.rst", """
                Reference
                =========

                Install
                -------

                %s

                Usage
                -----

                %s
                """.formatted(LONG_SENTENCE.repeat(2), LONG_SENTENCE.repeat(2)));

        assertEquals(List.of("Reference > Install", "Reference > Usage"), contexts(chunks));
        assertTrue(chunks.get(0).getText().startsWith("Install\n-------\n\nThis sentence"));
    }

    @Test
    void chunkFile_ShouldSplitPlainTextByParagraphs() throws IOException {
        final List<InformationChunk> chunks = chunk("notes.txt", LONG_SENTENCE.repeat(2) + "\r\n\r\n" + LONG_SENTENCE.repeat(2) + "\r\n");

        assertEquals(List.of(LONG_SENTENCE.repeat(2).strip(), LONG_SENTENCE.repeat(2).strip()), texts(chunks));
        assertEquals(List.of("notes.txt", "notes.txt"), contexts(chunks));
    }

    @Test
    void chunkFile_ShouldSplitYamlOnItsKeys_AndGoDeeperOnlyWhereASectionIsTooLong() throws IOException {
        final List<InformationChunk> chunks = chunk("application.yml", """
                # Server settings
                server:
                  port: 8080

                logging:
                  level:
                    root: INFO

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

        assertEquals(List.of("application.yml", "spring > datasource", "spring > jpa"), contexts(chunks));
        assertEquals("# Server settings\nserver:\n  port: 8080\nlogging:\n  level:\n    root: INFO", chunks.get(0).getText());
        assertTrue(chunks.get(1).getText().startsWith("  datasource:\n    url: jdbc:postgresql"));
        assertTrue(chunks.get(2).getText().startsWith("  jpa:\n    hibernate:"));
    }

    @Test
    void chunkFile_ShouldGroupPropertiesByTheFirstSegmentOfTheirKeys() throws IOException {
        final List<InformationChunk> chunks = chunk("application.properties", """
                # Datasource
                spring.datasource.url=jdbc:postgresql://localhost:5432/orders
                spring.datasource.username=orders
                spring.jpa.hibernate.ddl-auto=validate

                # Logging
                logging.level.root=INFO
                logging.pattern.console=%d{HH:mm:ss} \\
                  %-5level %msg%n

                server.port=8080
                """);

        assertEquals(List.of("spring", "application.properties"), contexts(chunks));
        assertTrue(chunks.get(0).getText().startsWith("# Datasource\nspring.datasource.url="));
        assertTrue(chunks.get(0).getText().endsWith("spring.jpa.hibernate.ddl-auto=validate"));
        assertEquals("# Logging\nlogging.level.root=INFO\nlogging.pattern.console=%d{HH:mm:ss} \\\n  %-5level %msg%n\nserver.port=8080",
                chunks.get(1).getText());
    }

    @Test
    void chunkFile_ShouldSplitJsonOnItsFields() throws IOException {
        final List<InformationChunk> chunks = chunk("package.json", """
                {"name": "orders-ui", "version": "1.0.0",
                 "scripts": {"build": "vite build", "test": "vitest run", "lint": "eslint src --max-warnings 0",
                             "preview": "vite preview --host 0.0.0.0 --port 4173", "format": "prettier --write src",
                             "dev": "vite"},
                 "dependencies": {"react": "18.2.0", "react-dom": "18.2.0", "react-router-dom": "6.22.0",
                                  "axios": "1.6.7", "zustand": "4.5.0"}}
                """);

        assertEquals(List.of("package.json", "scripts", "dependencies"), contexts(chunks));
        assertEquals("\"name\": \"orders-ui\",\n\"version\": \"1.0.0\"", chunks.get(0).getText());
        assertTrue(chunks.get(1).getText().startsWith("\"build\": \"vite build\",\n\"test\": \"vitest run\""));
        assertEquals("\"dependencies\": {\n  \"react\" : \"18.2.0\",\n  \"react-dom\" : \"18.2.0\",\n"
                + "  \"react-router-dom\" : \"6.22.0\",\n  \"axios\" : \"1.6.7\",\n  \"zustand\" : \"4.5.0\"\n}", chunks.get(2).getText());
    }

    @Test
    void chunkFile_ShouldSplitXmlOnItsElements_AndKeepTheirComments() throws IOException {
        final List<InformationChunk> chunks = chunk("pom.xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE project SYSTEM "http://localhost:1/missing.dtd">
                <project>
                    <groupId>com.example</groupId>
                    <artifactId>orders</artifactId>
                    <!-- Runtime dependencies -->
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-webflux</artifactId>
                        </dependency>
                        <dependency>
                            <groupId>org.postgresql</groupId>
                            <artifactId>postgresql</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """);

        assertEquals(List.of("pom.xml", "dependencies > dependency", "dependencies > dependency"), contexts(chunks));
        assertEquals("<groupId>com.example</groupId>\n<artifactId>orders</artifactId>", chunks.get(0).getText());
        assertTrue(chunks.get(1).getText().contains("spring-boot-starter-webflux"));
        assertTrue(chunks.get(2).getText().contains("<artifactId>postgresql</artifactId>"));
    }

    @Test
    void chunkFile_ShouldSplitByParagraphs_WhenTheStructureCannotBeRead() throws IOException {
        final List<InformationChunk> chunks = chunk("broken.json", "{\"name\": \"orders-ui\",\n\n" + LONG_SENTENCE.repeat(4));

        assertEquals(2, chunks.size());
        assertEquals("{\"name\": \"orders-ui\",", chunks.get(0).getText());
    }

    @Test
    void chunkFile_ShouldSplitByLines_WhenAParagraphIsLongerThanTheLimit() throws IOException {
        final List<InformationChunk> chunks = chunk("notes.txt", (LONG_SENTENCE.strip() + "\n").repeat(6));

        assertEquals(3, chunks.size());
        assertTrue(chunks.stream().allMatch(chunk -> chunk.getText().length() <= 200));
    }

    @Test
    void chunkFile_ShouldDropChunksShorterThanTenCharacters() throws IOException {
        assertTrue(chunk("notes.txt", "short\n").isEmpty());
    }

    @Test
    void chunkFile_ShouldDescribeEachChunk() throws IOException {
        final InformationChunk chunk = chunk("guide.md", "A paragraph long enough to be kept.").get(0);

        assertEquals("docs-project", chunk.getProjectName());
        assertEquals("guide.md", chunk.getFilePath());
        assertEquals("MD", chunk.getLanguage());
        assertEquals(InformationType.DOC, chunk.getInformationType());
        assertEquals("text", chunk.getChunkingStrategy());
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
