package com.mtcc.common.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class IngestionProgressTest {

    private final IngestionProgress ingestionProgress = new IngestionProgress();

    private long occurrences(final CapturedOutput output, final String text) {
        return output.getOut().lines().filter(line -> line.contains(text)).count();
    }

    @Test
    void logProjectSummary_ShouldReportTheCountersOfTheProject(final CapturedOutput output) {
        ingestionProgress.start("spring-ai");
        ingestionProgress.fileProcessed("spring-ai");
        ingestionProgress.fileProcessed("spring-ai");
        ingestionProgress.fileSkipped("spring-ai");
        ingestionProgress.chunksUpserted("spring-ai", 75);
        ingestionProgress.chunksFailed("spring-ai", 5);
        ingestionProgress.secretsRedacted("spring-ai", 3);

        ingestionProgress.logProjectSummary("spring-ai");

        assertTrue(output.getOut().contains(
                "2 files processed (1 skipped), 75 chunks upserted (5 failed), 3 secrets redacted"), output.getOut());
    }

    @Test
    void logSummary_ShouldAddUpTheCountersOfEveryProject(final CapturedOutput output) {
        ingestionProgress.start("spring-ai");
        ingestionProgress.start("spring-security");
        ingestionProgress.fileProcessed("spring-ai");
        ingestionProgress.fileProcessed("spring-security");
        ingestionProgress.chunksUpserted("spring-ai", 10);
        ingestionProgress.chunksUpserted("spring-security", 20);

        ingestionProgress.logSummary(Duration.ofSeconds(125));

        assertTrue(output.getOut().contains(
                "Ingestion completed in 2m 05s: 2 projects, 2 files processed (0 skipped), 30 chunks upserted (0 failed), "
                        + "0 secrets redacted"), output.getOut());
    }

    @Test
    void chunksUpserted_ShouldLogProgressOnlyWhenAThousandChunksAreCrossed(final CapturedOutput output) {
        ingestionProgress.start("spring-ai");

        for (int batch = 0; batch < 27; batch++) {
            ingestionProgress.chunksUpserted("spring-ai", 75);
        }

        assertEquals(2, occurrences(output, "chunks upserted so far"));
        assertTrue(output.getOut().contains("spring-ai: 1050 chunks upserted so far"), output.getOut());
        assertTrue(output.getOut().contains("spring-ai: 2025 chunks upserted so far"), output.getOut());
    }

    @Test
    void start_ShouldResetTheCountersOfAProject(final CapturedOutput output) {
        ingestionProgress.start("spring-ai");
        ingestionProgress.fileProcessed("spring-ai");

        ingestionProgress.start("spring-ai");
        ingestionProgress.logProjectSummary("spring-ai");

        assertTrue(output.getOut().contains("0 files processed (0 skipped)"), output.getOut());
        assertFalse(output.getOut().contains("1 files processed"), output.getOut());
    }
}
