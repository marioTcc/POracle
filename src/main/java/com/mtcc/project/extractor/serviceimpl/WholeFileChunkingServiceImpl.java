package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.file.Path;

import static com.mtcc.common.entity.InformationChunk.buildChunk;
import static com.mtcc.project.extractor.util.FileUtils.getLowerCaseExtension;
import static com.mtcc.project.extractor.util.FileUtils.getRelativePath;
import static com.mtcc.project.extractor.util.OutlineUtils.keepFirstLines;

@Slf4j
@Service("wholeFileChunkingServiceImpl")
@RequiredArgsConstructor
public class WholeFileChunkingServiceImpl implements IChunkingServiceImpl {

    private static final String STRATEGY = "file-head";

    private final IIOServiceImpl ioService;
    private final ChunkingProperties chunkingProperties;

    @Override
    public Flux<InformationChunk> chunkFile(final Project project, final Path filePath, final InformationType infoType) {
        return ioService.readText(filePath)
                .map(data -> buildChunk(project.getProjectName(), getRelativePath(project, filePath),
                        getLowerCaseExtension(filePath), infoType,
                        keepFirstLines(data, chunkingProperties.getFileOutlineMaxChars()),
                        filePath.getFileName().toString(), STRATEGY))
                .flux()
                .doOnSubscribe(subscription -> log.debug("Outlining {} as {} by its first lines", filePath.getFileName(), infoType))
                .doOnError(error -> log.warn("Cannot read {}, the file is skipped: {}", filePath, error.toString()));
    }
}
