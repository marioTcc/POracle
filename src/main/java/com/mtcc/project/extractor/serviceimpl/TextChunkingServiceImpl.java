package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.project.extractor.serviceimpl.section.PackedSection;
import com.mtcc.project.extractor.serviceimpl.section.SectionPacker;
import com.mtcc.project.extractor.serviceimpl.section.SectionParser;
import com.mtcc.project.extractor.serviceimpl.section.TextSection;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.mtcc.common.entity.InformationChunk.buildChunk;
import static com.mtcc.project.extractor.config.SectionParserConfig.ANY_EXTENSION;
import static com.mtcc.project.extractor.util.FileUtils.getLowerCaseExtension;
import static com.mtcc.project.extractor.util.FileUtils.getRelativePath;

@Slf4j
@Service("textChunkingServiceImpl")
@RequiredArgsConstructor
public class TextChunkingServiceImpl implements IChunkingServiceImpl {

    private static final int MIN_CHUNK_CHARS = 10;

    private final ChunkingProperties chunkingProperties;
    private final IIOServiceImpl ioService;
    private final Map<String, SectionParser> sectionParsers;

    @Override
    public Flux<InformationChunk> chunkFile(final Project project, final Path filePath, final InformationType infoType) {
        final String extension = getLowerCaseExtension(filePath);
        final String fileName = filePath.getFileName().toString();

        return ioService.readText(filePath)
                .flatMapIterable(content -> getSections(content, extension, fileName))
                .map(section -> buildChunk(project.getProjectName(), getRelativePath(project, filePath), extension,
                        infoType, section.getText(),
                        section.getContext().isEmpty() ? fileName : section.getContext(), "text"))
                .doOnSubscribe(subscription -> log.debug("Chunking {} as {} by its sections", fileName, infoType))
                .doOnError(error -> log.debug("Cannot chunk {} by its sections", filePath, error));
    }

    private List<PackedSection> getSections(final String content, final String extension, final String fileName) {
        final SectionParser paragraphParser = sectionParsers.get(ANY_EXTENSION);
        final TextSection structure = Try.of(() -> sectionParsers.getOrDefault(extension, paragraphParser).parse(content))
                .getOrElseGet(error -> {
                    log.debug("Cannot read the structure of {}, splitting it by paragraphs: {}", fileName, error.toString());
                    return paragraphParser.parse(content);
                });

        return new SectionPacker(chunkingProperties.getTextChunkMaxChars()).pack(structure).stream()
                .filter(section -> section.getText().strip().length() >= MIN_CHUNK_CHARS)
                .toList();
    }
}
