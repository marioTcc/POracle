package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.serviceimpl.interfaces.IRepositoryFileServiceImpl;
import com.mtcc.project.extractor.config.GitHubClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubFileServiceImpl implements IRepositoryFileServiceImpl {

    private static final Pattern REPOSITORY_URL = Pattern.compile("github\\.com[/:](?<owner>[\\w.-]+)/(?<repository>[\\w.-]+?)(\\.git)?/?$");
    private static final String RAW_CONTENT = "application/vnd.github.raw+json";
    private static final String DEFAULT_REVISION = "HEAD";

    private final WebClient gitHubClient;
    private final GitHubClientProperties properties;

    @Override
    public Mono<String> readFile(final String repositoryUrl, final String revision, final String filePath) {
        final Matcher repository = REPOSITORY_URL.matcher(StringUtils.trimToEmpty(repositoryUrl));

        if (!repository.find()) {
            return Mono.error(new IllegalArgumentException("Not a GitHub repository: " + repositoryUrl));
        }

        return gitHubClient.get()
                .uri(uri -> uri.path("/repos/{owner}/{repository}/contents/")
                        .path(filePath)
                        .queryParam("ref", StringUtils.defaultIfBlank(revision, DEFAULT_REVISION))
                        .build(repository.group("owner"), repository.group("repository")))
                .headers(this::authorize)
                .header(HttpHeaders.ACCEPT, RAW_CONTENT)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSubscribe(subscription -> log.info("Reading {} of {} at {} from GitHub", filePath, repositoryUrl, revision))
                .doOnError(error -> log.warn("Cannot read {} of {} from GitHub: {}", filePath, repositoryUrl, error.toString()));
    }

    private void authorize(final HttpHeaders headers) {
        if (StringUtils.isNotBlank(properties.getGithubApiKey())) {
            headers.setBearerAuth(properties.getGithubApiKey());
        }
    }
}
