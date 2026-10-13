package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import com.mtcc.project.extractor.serviceimpl.section.SectionParser;
import com.mtcc.project.extractor.serviceimpl.section.TextSection;
import io.vavr.control.Try;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.mtcc.common.entity.InformationChunk.buildChunk;
import static com.mtcc.project.extractor.config.SectionParserConfig.ANY_EXTENSION;
import static com.mtcc.project.extractor.util.FileUtils.getLowerCaseExtension;
import static com.mtcc.project.extractor.util.FileUtils.getRelativePath;
import static com.mtcc.project.extractor.util.OutlineUtils.keepFirstLines;

@Slf4j
@Service("textChunkingServiceImpl")
@RequiredArgsConstructor
public class TextChunkingServiceImpl implements IChunkingServiceImpl {

    private static final int MIN_CHUNK_CHARS = 10;
    private static final int MAX_SECTION_DEPTH = 3;
    private static final String STRATEGY = "text-outline";
    private static final String SECTIONS_LABEL = "\n\nSections:\n";
    private static final String INDENTATION = "  ";

    private final ChunkingProperties chunkingProperties;
    private final IIOServiceImpl ioService;
    private final Map<String, SectionParser> sectionParsers;

    @Override
    public Flux<InformationChunk> chunkFile(final Project project, final Path filePath, final InformationType infoType) {
        final String extension = getLowerCaseExtension(filePath);
        final String fileName = filePath.getFileName().toString();

        return ioService.readText(filePath)
                .map(content -> getOutline(content, extension, fileName))
                .filter(outline -> outline.length() >= MIN_CHUNK_CHARS)
                .map(outline -> buildChunk(project.getProjectName(), getRelativePath(project, filePath), extension,
                        infoType, outline, fileName, STRATEGY))
                .flux()
                .doOnSubscribe(subscription -> log.debug("Outlining {} as {} by its sections", fileName, infoType))
                .doOnError(error -> log.debug("Cannot outline {} by its sections", filePath, error));
    }

    private String getOutline(final String content, final String extension, final String fileName) {
        final int maxChars = chunkingProperties.getFileOutlineMaxChars();

        if (content.strip().length() <= maxChars) {
            return content.strip();
        }

        final String sections = getSectionLabels(getStructure(content, extension, fileName), 0)
                .collect(Collectors.joining("\n"));

        return sections.isEmpty() ?
                keepFirstLines(content, maxChars) :
                keepFirstLines(content, maxChars / 2) + SECTIONS_LABEL + keepFirstLines(sections, maxChars / 2);
    }

    private TextSection getStructure(final String content, final String extension, final String fileName) {
        final SectionParser paragraphParser = sectionParsers.get(ANY_EXTENSION);

        return Try.of(() -> sectionParsers.getOrDefault(extension, paragraphParser).parse(content))
                .getOrElseGet(error -> {
                    log.debug("Cannot read the structure of {}: {}", fileName, error.toString());
                    return paragraphParser.parse(content);
                });
    }

    private Stream<String> getSectionLabels(final TextSection section, final int depth) {
        if (depth >= MAX_SECTION_DEPTH) {
            return Stream.empty();
        }

        return section.getChildren().stream()
                .filter(child -> !child.getLabel().isEmpty())
                .flatMap(child -> Stream.concat(
                        Stream.of(INDENTATION.repeat(depth) + child.getLabel()),
                        getSectionLabels(child, depth + 1)));
    }
}
