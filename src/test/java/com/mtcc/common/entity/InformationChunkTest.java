package com.mtcc.common.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class InformationChunkTest {

    private InformationChunk buildCodeChunk(final String content) {
        return InformationChunk.buildChunk("spring-ai", "models/openai/src/OpenAiApi.java", "JAVA",
                InformationType.CODE, content, "org.example.OpenAiApi", "code");
    }

    @Test
    void buildChunk_ShouldTakeTheFileNameFromThePath() {
        final InformationChunk chunk = buildCodeChunk("void call() {}");

        assertEquals("models/openai/src/OpenAiApi.java", chunk.getFilePath());
        assertEquals("OpenAiApi.java", chunk.getFilename());
    }

    @Test
    void buildChunk_ShouldGenerateTheSameIdForTheSameChunk() {
        assertEquals(buildCodeChunk("void call() {}").getChunkId(), buildCodeChunk("void call() {}").getChunkId());
        assertNotEquals(buildCodeChunk("void call() {}").getChunkId(), buildCodeChunk("void other() {}").getChunkId());
    }

    @Test
    void buildChunk_ShouldReplaceAMissingContextWithAnEmptyOne() {
        final InformationChunk chunk = InformationChunk.buildChunk("spring-ai", "", "TEXT",
                InformationType.PROJECT_STRUCTURE, "A file tree.", null, "llm-summary");

        assertEquals("", chunk.getContext());
        assertEquals("", chunk.getFilename());
    }

    @Test
    void buildChunk_ShouldLabelTheLanguageInUpperCase_AndFilesWithoutOneAsText() {
        assertEquals("MD", InformationChunk.buildChunk("spring-ai", "docs/guide.md", "md",
                InformationType.DOC, "A guide.", "guide.md", "text").getLanguage());
        assertEquals("TEXT", InformationChunk.buildChunk("spring-ai", "LICENSE", "",
                InformationType.DOC, "Apache License", "LICENSE", "whole-file").getLanguage());
        assertEquals("TEXT", InformationChunk.buildChunk("spring-ai", "LICENSE", null,
                InformationType.DOC, "Apache License", "LICENSE", "whole-file").getLanguage());
    }

    @Test
    void toEmbeddingText_ShouldContainOnlyProjectFileContextAndText() {
        final InformationChunk chunk = buildCodeChunk("void call() {}");

        assertEquals("Project: spring-ai\nFile: models/openai/src/OpenAiApi.java\nContext: org.example.OpenAiApi\n\nvoid call() {}",
                chunk.toEmbeddingText());
    }

    @Test
    void toEmbeddingText_ShouldLeaveOutTheContext_WhenItIsJustTheFileName() {
        final InformationChunk chunk = InformationChunk.buildChunk("spring-ai", "docs/guide.md", "MD",
                InformationType.DOC, "A guide.", "guide.md", "text");

        assertEquals("Project: spring-ai\nFile: docs/guide.md\n\nA guide.", chunk.toEmbeddingText());
    }
}
