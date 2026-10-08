package com.mtcc.common.entity;

import lombok.Builder;
import lombok.Getter;
import org.apache.commons.lang3.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

@Builder(toBuilder = true)
@Getter
public class InformationChunk {

    private static final String DEFAULT_LANGUAGE = "TEXT";

    private String projectName;
    private String chunkId;
    private String filename;
    private String filePath;
    private String language;
    private InformationType informationType;
    private String text;
    private String chunkingStrategy;
    private String context;
    private String ingestionId;

    public String toEmbeddingText() {
        final StringBuilder embeddingText = new StringBuilder("Project: ").append(projectName);

        if (StringUtils.isNotBlank(filePath)) {
            embeddingText.append("\nFile: ").append(filePath);
        }
        if (StringUtils.isNotBlank(context) && !StringUtils.equals(context, filename)) {
            embeddingText.append("\nContext: ").append(context);
        }

        return embeddingText.append("\n\n").append(text).toString();
    }

    public static InformationChunk buildChunk(
            final String projectName, final String filePath, final String language,
            final InformationType informationType, final String content, final String context,
            final String chunkingStrategy) {
        return InformationChunk.builder()
                .projectName(projectName)
                .chunkId(buildChunkId(projectName, filePath, chunkingStrategy, content))
                .filename(StringUtils.substringAfterLast("/" + filePath, "/"))
                .filePath(filePath)
                .language(StringUtils.isBlank(language) ? DEFAULT_LANGUAGE : language.toUpperCase(Locale.ROOT))
                .informationType(informationType)
                .text(content)
                .context(StringUtils.defaultString(context))
                .chunkingStrategy(chunkingStrategy)
                .build();
    }

    private static String buildChunkId(
            final String projectName, final String filePath, final String chunkingStrategy, final String content) {
        return UUID.nameUUIDFromBytes(String.join("|", projectName, filePath, chunkingStrategy, content)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }
}
