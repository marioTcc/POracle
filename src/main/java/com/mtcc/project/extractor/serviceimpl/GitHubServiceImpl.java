package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.project.extractor.config.GitHubClientProperties;
import com.mtcc.project.extractor.serviceimpl.interfaces.IGitServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static com.mtcc.project.extractor.util.ProjectNamingUtils.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubServiceImpl implements IGitServiceImpl {

    private static final String DISABLE_CONTENT_FILTERS = "* -filter\n";

    private final GitHubClientProperties properties;
    private final IIOServiceImpl ioService;

    @Override
    public Mono<Path> cloneResource(final String url, final Path directory) {
        final long startedAt = System.nanoTime();

        return Mono.just(directory)
                .map(Path::toFile)
                .doOnNext(path -> log.info("Cloning {} into {}", url, path))
                .map(dir -> Try.withResources(() -> Git.cloneRepository()
                                .setCredentialsProvider(
                                        new UsernamePasswordCredentialsProvider("", properties.getGithubApiKey()))
                                .setURI(url)
                                .setDepth(properties.getCloneDepth())
                                .setCloneAllBranches(properties.getCloneAllBranches())
                                .setDirectory(dir)
                                .setNoTags()
                                .setNoCheckout(true)
                                .call())
                        .of(this::checkoutWithoutContentFilters)
                        .map(git -> directory)
                        .getOrElseThrow(Exceptions::propagate))
                .doOnNext(path -> log.info("Cloned {} in {}s", url,
                        Duration.ofNanos(System.nanoTime() - startedAt).toSeconds()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Git checkoutWithoutContentFilters(final Git git) throws IOException, GitAPIException {
        final Path repositoryAttributes =
                git.getRepository().getDirectory().toPath().resolve("info").resolve("attributes");

        Files.createDirectories(repositoryAttributes.getParent());
        Files.writeString(repositoryAttributes, DISABLE_CONTENT_FILTERS);
        git.reset().setMode(ResetCommand.ResetType.HARD).call();
        return git;
    }

    @Override
    public Mono<Path> cloneRepository(final String projectUrl) {
        return ioService.createFolder(urltoProjectName.apply(projectUrl))
                .flatMap(newDir -> this.cloneResource(projectUrl, newDir))
                .doOnError(error -> log.error("Cannot clone the project {}", projectUrl, error));
    }

    @Override
    public Mono<Path> cloneWiki(final String projectUrl) {
        return ioService.createFolder(urlToProjectWikiName.apply(projectUrl))
                .flatMap(newDir -> this.cloneResource(toWikiUrl.apply(projectUrl), newDir))
                .doOnError(error -> log.warn("Cannot clone the wiki of {}: {}", projectUrl, error.getMessage()));
    }
}
