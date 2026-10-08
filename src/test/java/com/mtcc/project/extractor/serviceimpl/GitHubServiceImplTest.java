package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.project.extractor.config.GitHubClientProperties;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.attributes.FilterCommand;
import org.eclipse.jgit.attributes.FilterCommandRegistry;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.util.SystemReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GitHubServiceImplTest {

    private static final String FILTER_NAME = "poracle-test";
    private static final String SMUDGE_COMMAND = "jgit://builtin/poracle-test/smudge";

    @TempDir
    private Path tempDir;

    @Mock
    private GitHubClientProperties properties;
    @Mock
    private IIOServiceImpl ioService;
    @InjectMocks
    private GitHubServiceImpl gitService;

    private Path remoteRepository;

    @BeforeEach
    void setUp() throws Exception {
        when(properties.getGithubApiKey()).thenReturn("");
        when(properties.getCloneDepth()).thenReturn(1);
        when(properties.getCloneAllBranches()).thenReturn(false);

        remoteRepository = Files.createDirectory(tempDir.resolve("sample-project"));
        try (Git git = Git.init().setDirectory(remoteRepository.toFile()).call()) {
            Files.writeString(remoteRepository.resolve("README.md"), "# Sample project");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("first commit").setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com").setSign(false).call();
        }
    }

    private String remoteUrl() {
        return remoteRepository.toUri().toString().replaceAll("/$", "");
    }

    private void delete(final Path folder) throws IOException {
        try (Stream<Path> files = Files.walk(folder)) {
            for (final Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                file.toFile().setWritable(true);
                Files.delete(file);
            }
        }
    }

    @Test
    void cloneResource_ShouldCloneTheRepositoryIntoTheGivenFolder() {
        final Path target = tempDir.resolve("clone");

        final Path cloned = gitService.cloneResource(remoteUrl(), target).block();

        assertEquals(target, cloned);
        assertTrue(Files.exists(target.resolve("README.md")));
    }

    @Test
    void cloneResource_ShouldReleaseTheRepositoryFiles_SoTheFolderCanBeDeleted() throws IOException {
        final Path target = tempDir.resolve("clone");
        gitService.cloneResource(remoteUrl(), target).block();

        delete(target);

        assertFalse(Files.exists(target));
    }

    @Test
    void cloneResource_ShouldFail_WhenTheRepositoryDoesNotExist() {
        final String missingRepository = tempDir.resolve("missing").toUri().toString();

        assertThrows(RuntimeException.class,
                () -> gitService.cloneResource(missingRepository, tempDir.resolve("clone")).block());
    }

    @Test
    void cloneRepository_ShouldCloneIntoTheFolderNamedAfterTheProject() {
        final Path target = tempDir.resolve("downloads").resolve("sample-project");
        when(ioService.createFolder("sample-project")).thenReturn(Mono.just(target));

        final Path cloned = gitService.cloneRepository(remoteUrl()).block();

        assertEquals(target, cloned);
        assertTrue(Files.exists(target.resolve("README.md")));
    }

    @Test
    void cloneResource_ShouldNotRunTheContentFiltersOfTheRepository() throws Exception {
        final AtomicInteger filterRuns = new AtomicInteger();
        final StoredConfig userConfig = SystemReader.getInstance().getUserConfig();
        FilterCommandRegistry.register(SMUDGE_COMMAND, (repository, in, out) -> {
            filterRuns.incrementAndGet();
            return new FilterCommand(in, out) {
                @Override
                public int run() throws IOException {
                    in.transferTo(out);
                    in.close();
                    out.close();
                    return -1;
                }
            };
        });
        userConfig.setString("filter", FILTER_NAME, "smudge", SMUDGE_COMMAND);

        try (Git git = Git.open(remoteRepository.toFile())) {
            Files.writeString(remoteRepository.resolve(".gitattributes"), "*.bin filter=" + FILTER_NAME + "\n");
            Files.writeString(remoteRepository.resolve("model.bin"), "pointer to a large file");
            git.add().addFilepattern(".").call();
            git.commit().setMessage("large file").setAuthor("test", "test@example.com")
                    .setCommitter("test", "test@example.com").setSign(false).call();

            try (Git plainClone = Git.cloneRepository().setURI(remoteUrl())
                    .setDirectory(tempDir.resolve("plain-clone").toFile()).call()) {
                assertEquals(1, filterRuns.getAndSet(0), "A plain clone must run the filter, otherwise this test proves nothing");
            }

            final Path target = tempDir.resolve("clone");
            gitService.cloneResource(remoteUrl(), target).block();

            assertEquals("pointer to a large file", Files.readString(target.resolve("model.bin")));
            assertTrue(Files.exists(target.resolve("README.md")));
            assertEquals(0, filterRuns.get());
        } finally {
            userConfig.unsetSection("filter", FILTER_NAME);
            FilterCommandRegistry.unregister(SMUDGE_COMMAND);
        }
    }
}
