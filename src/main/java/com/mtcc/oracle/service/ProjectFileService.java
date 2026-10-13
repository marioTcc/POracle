package com.mtcc.oracle.service;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.common.security.SecretRedactor;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IRepositoryFileServiceImpl;
import com.mtcc.oracle.config.AgentProperties;
import com.mtcc.oracle.entity.ProjectFile;
import com.mtcc.oracle.exception.ProjectFileNotFoundException;
import com.mtcc.rag.service.RagService;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ProjectFileService {

    private static final Set<InformationType> FILE_TYPES =
            Set.of(InformationType.CODE, InformationType.DOC, InformationType.CONFIG);

    private final RagService ragService;
    private final IIOServiceImpl ioService;
    private final IRepositoryFileServiceImpl repositoryFileService;
    private final SecretRedactor secretRedactor;
    private final AgentProperties agentProperties;

    public Mono<ProjectFile> readFile(final String projectName, final String path) {
        final String filePath = normalize(path);

        return ragService.findChunksByPath(projectName, filePath)
                .flatMap(chunks -> Mono.justOrEmpty(getNode(chunks)))
                .switchIfEmpty(Mono.error(() -> new ProjectFileNotFoundException(
                        "'" + filePath + "' is not a file or folder of project '" + projectName + "'")))
                .flatMap(this::read);
    }

    private Optional<InformationChunk> getNode(final List<InformationChunk> chunks) {
        return chunks.stream()
                .filter(chunk -> isFolder(chunk) || FILE_TYPES.contains(chunk.getInformationType()))
                .findFirst();
    }

    private Mono<ProjectFile> read(final InformationChunk node) {
        if (isFolder(node)) {
            return Mono.just(ProjectFile.builder().node(node).content(node.getText()).folder(true).build());
        }

        return readLocalFile(node)
                .onErrorResume(error -> Mono.empty())
                .switchIfEmpty(Mono.defer(() -> readRepositoryFile(node)))
                .switchIfEmpty(Mono.error(() -> new ProjectFileNotFoundException(
                        "The content of '" + node.getFilePath() + "' is no longer available")))
                .map(content -> ProjectFile.builder().node(node).content(redactSecrets(node, content)).build());
    }

    private Mono<String> readLocalFile(final InformationChunk node) {
        return Mono.justOrEmpty(node.getLocalPath())
                .filter(StringUtils::isNotBlank)
                .flatMap(localPath -> ioService.readText(Path.of(localPath)));
    }

    private Mono<String> readRepositoryFile(final InformationChunk node) {
        return Mono.justOrEmpty(node.getRepositoryUrl())
                .filter(StringUtils::isNotBlank)
                .flatMap(repositoryUrl -> repositoryFileService.readFile(repositoryUrl, node.getRevision(), node.getFilePath()));
    }

    private String redactSecrets(final InformationChunk node, final String content) {
        return Boolean.TRUE.equals(agentProperties.getSecretsRedactionEnabled()) ?
                secretRedactor.redact(content, node.getInformationType() == InformationType.CONFIG).getText() :
                content;
    }

    private boolean isFolder(final InformationChunk chunk) {
        return chunk.getInformationType() == InformationType.FOLDER_SUMMARY;
    }

    private String normalize(final String path) {
        return StringUtils.strip(StringUtils.trimToEmpty(path).replace('\\', '/'), "/");
    }
}
