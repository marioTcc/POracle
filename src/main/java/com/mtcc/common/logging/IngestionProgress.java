package com.mtcc.common.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

@Slf4j
@Component
public class IngestionProgress {

    private static final int PROGRESS_STEP = 1000;

    private final Map<String, ProjectProgress> progressByProject = new ConcurrentHashMap<>();

    public void start(final String projectName) {
        progressByProject.put(projectName, new ProjectProgress());
    }

    public void fileProcessed(final String projectName) {
        getProgress(projectName).files.incrementAndGet();
    }

    public void fileSkipped(final String projectName) {
        getProgress(projectName).skippedFiles.incrementAndGet();
    }

    public void chunksUpserted(final String projectName, final int count) {
        final long total = getProgress(projectName).chunks.addAndGet(count);

        if (total / PROGRESS_STEP > (total - count) / PROGRESS_STEP) {
            log.info("{}: {} chunks upserted so far", projectName, total);
        }
    }

    public void chunksFailed(final String projectName, final int count) {
        getProgress(projectName).failedChunks.addAndGet(count);
    }

    public void secretsRedacted(final String projectName, final int count) {
        getProgress(projectName).redactedSecrets.addAndGet(count);
    }

    public boolean hasFailures(final String projectName) {
        final ProjectProgress progress = getProgress(projectName);

        return progress.failedChunks.get() > 0 || progress.skippedFiles.get() > 0;
    }

    public void logProjectSummary(final String projectName) {
        final ProjectProgress progress = getProgress(projectName);

        log.info("{} ingested in {}: {} files processed ({} skipped), {} chunks upserted ({} failed), {} secrets redacted",
                projectName, format(progress.getElapsed()), progress.files.get(), progress.skippedFiles.get(),
                progress.chunks.get(), progress.failedChunks.get(), progress.redactedSecrets.get());
    }

    public void logSummary(final Duration elapsed) {
        log.info("Ingestion completed in {}: {} projects, {} files processed ({} skipped), {} chunks upserted ({} failed), "
                        + "{} secrets redacted",
                format(elapsed), progressByProject.size(),
                sum(progress -> progress.files), sum(progress -> progress.skippedFiles),
                sum(progress -> progress.chunks), sum(progress -> progress.failedChunks),
                sum(progress -> progress.redactedSecrets));
    }

    private ProjectProgress getProgress(final String projectName) {
        return progressByProject.computeIfAbsent(projectName, name -> new ProjectProgress());
    }

    private long sum(final Function<ProjectProgress, AtomicLong> counter) {
        return progressByProject.values().stream()
                .mapToLong(progress -> counter.apply(progress).get())
                .sum();
    }

    private static String format(final Duration elapsed) {
        return String.format("%dm %02ds", elapsed.toMinutes(), elapsed.toSecondsPart());
    }

    private static class ProjectProgress {
        private final long startedAt = System.nanoTime();
        private final AtomicLong files = new AtomicLong();
        private final AtomicLong skippedFiles = new AtomicLong();
        private final AtomicLong chunks = new AtomicLong();
        private final AtomicLong failedChunks = new AtomicLong();
        private final AtomicLong redactedSecrets = new AtomicLong();

        private Duration getElapsed() {
            return Duration.ofNanos(System.nanoTime() - startedAt);
        }
    }
}
