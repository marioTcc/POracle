package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.config.TSLanguageConfig;
import com.mtcc.project.extractor.entity.Project;
import com.mtcc.project.extractor.serviceimpl.interfaces.IChunkingServiceImpl;
import com.mtcc.common.serviceimpl.interfaces.IIOServiceImpl;
import io.vavr.control.Try;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.treesitter.*;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.mtcc.common.entity.InformationChunk.buildChunk;
import static com.mtcc.project.extractor.util.FileUtils.getLowerCaseExtension;
import static com.mtcc.project.extractor.util.FileUtils.getRelativePath;
import static com.mtcc.project.extractor.util.OutlineUtils.keepFirstLines;

@Slf4j
@Service("codeChunkingServiceImpl")
@RequiredArgsConstructor
public class CodeChunkingServiceImpl implements IChunkingServiceImpl {

    private static final String CHUNK_CAPTURE = "chunk";
    private static final String STRATEGY = "code-outline";
    private static final String ENTRY_SEPARATOR = "\n\n";
    private static final String LINE_END = "\n";
    private static final String WINDOWS_LINE_END = "\r\n";
    private static final int MIN_CHUNK_CHARS = 10;
    private static final int MAX_WHOLE_DECLARATION_CHARS = 300;
    private static final String PACKAGE_KEYWORD = "package";
    private static final String STATEMENT_END = ";";
    private static final String CLASS_BODY = "class_body";
    private static final String IN_LABEL = "In ";
    private static final String FIELDS_LABEL = "Fields:\n";
    private static final String METHODS_LABEL = "Methods: ";
    private static final Set<String> OUTLINED_DECLARATIONS = Set.of("class_declaration", "object_declaration",
            "companion_object", "abstract_class_declaration");
    private static final Set<String> TYPE_DECLARATIONS = Set.of("class_declaration", "object_declaration",
            "abstract_class_declaration", "interface_declaration", "enum_declaration", "record_declaration",
            "annotation_type_declaration", "class_definition");
    private static final Set<String> FIELD_DECLARATIONS = Set.of("field_declaration", "property_declaration",
            "field_definition", "public_field_definition");
    private static final Set<String> METHOD_DECLARATIONS = Set.of("method_declaration", "constructor_declaration",
            "function_declaration", "method_definition");
    private static final Set<String> PACKAGE_DECLARATIONS = Set.of("package_declaration", "package_header");
    private static final Set<String> NAMES = Set.of("type_identifier", "simple_identifier", "identifier");
    private static final Set<String> COMMENTS = Set.of("block_comment", "line_comment", "multiline_comment",
            "comment", "marginalia");

    private final Map<String, TSLanguageConfig> tsConfig;
    private final IIOServiceImpl ioService;
    private final ChunkingProperties chunkingProperties;

    @Override
    public Flux<InformationChunk> chunkFile(
            final Project project, final Path filePath, final InformationType infoType) {
        return Mono.just(getLowerCaseExtension(filePath))
                .filter(tsConfig::containsKey)
                .flatMapMany(ext -> ioService.readText(filePath)
                        .publishOn(Schedulers.boundedElastic())
                        .map(source -> source.replace(WINDOWS_LINE_END, LINE_END))
                        .flatMap(source -> Mono.fromCallable(() -> parseOutline(tsConfig.get(ext), source)))
                        .filter(outline -> outline.getText().length() >= MIN_CHUNK_CHARS)
                        .map(outline -> buildChunk(project.getProjectName(), getRelativePath(project, filePath), ext,
                                infoType, keepFirstLines(outline.getText(), chunkingProperties.getFileOutlineMaxChars()),
                                outline.getScope().isEmpty() ? filePath.getFileName().toString() : outline.getScope(),
                                STRATEGY)))
                .doOnSubscribe(subscription -> log.debug("Outlining {} as {} by its declarations", filePath.getFileName(), infoType))
                .doOnError(error -> log.debug("Cannot outline {} by its declarations", filePath, error));
    }

    private synchronized CodeOutline parseOutline(final TSLanguageConfig languageConfig, final String source) {
        final byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);

        return Try.withResources(() -> languageConfig.getParser().parseString(null, source), TSQueryCursor::new)
                .of((tree, cursor) -> {
                    final String packageName = getPackageName(tree.getRootNode(), sourceBytes);
                    cursor.exec(languageConfig.getQuery(), tree.getRootNode());

                    final List<TSNode> chunkNodes = getChunkNodes(cursor, languageConfig.getQuery()).stream()
                            .filter(chunkNode -> !isListedInClassOutline(chunkNode))
                            .toList();
                    final List<TSNode> wholeNodes = chunkNodes.stream().filter(this::isKeptWhole).toList();

                    return CodeOutline.builder()
                            .scope(packageName)
                            .text(chunkNodes.stream()
                                    .filter(chunkNode -> wholeNodes.stream().noneMatch(wholeNode -> isInside(chunkNode, wholeNode)))
                                    .map(chunkNode -> getOutlineEntry(chunkNode, sourceBytes, packageName))
                                    .collect(Collectors.joining(ENTRY_SEPARATOR))
                                    .strip())
                            .build();
                })
                .getOrElseThrow(Exceptions::propagate);
    }

    private List<TSNode> getChunkNodes(final TSQueryCursor cursor, final TSQuery query) {
        return Stream.generate(TSQueryMatch::new)
                .takeWhile(cursor::nextMatch)
                .flatMap(match -> Arrays.stream(match.getCaptures()))
                .filter(capture -> CHUNK_CAPTURE.equals(query.getCaptureNameForId(capture.getIndex())))
                .map(TSQueryCapture::getNode)
                .toList();
    }

    private boolean isListedInClassOutline(final TSNode chunkNode) {
        final TSNode body = chunkNode.getParent();

        return (METHOD_DECLARATIONS.contains(chunkNode.getType()) || FIELD_DECLARATIONS.contains(chunkNode.getType()))
                && isPresent(body)
                && isPresent(body.getParent())
                && OUTLINED_DECLARATIONS.contains(body.getParent().getType());
    }

    private String getOutlineEntry(final TSNode chunkNode, final byte[] sourceBytes, final String packageName) {
        if (!OUTLINED_DECLARATIONS.contains(chunkNode.getType())) {
            return getLeadingComment(chunkNode, sourceBytes) + getDeclaration(chunkNode, sourceBytes);
        }

        final String scope = getScope(chunkNode.getParent(), sourceBytes, packageName);

        return (scope.isEmpty() ? "" : IN_LABEL + scope + "\n")
                + getLeadingComment(chunkNode, sourceBytes)
                + getClassOutline(chunkNode, sourceBytes);
    }

    private String getDeclaration(final TSNode node, final byte[] sourceBytes) {
        final String declaration = getText(node, sourceBytes).strip();

        return isKeptWhole(node) ? declaration : StringUtils.substringBefore(declaration, LINE_END).stripTrailing();
    }

    private boolean isKeptWhole(final TSNode node) {
        return !OUTLINED_DECLARATIONS.contains(node.getType())
                && node.getEndByte() - node.getStartByte() <= MAX_WHOLE_DECLARATION_CHARS;
    }

    private boolean isInside(final TSNode node, final TSNode container) {
        return container.getStartByte() <= node.getStartByte()
                && node.getEndByte() <= container.getEndByte()
                && node.getEndByte() - node.getStartByte() < container.getEndByte() - container.getStartByte();
    }

    private String getPackageName(final TSNode rootNode, final byte[] sourceBytes) {
        return Optional.ofNullable(getNamedChild(rootNode, PACKAGE_DECLARATIONS))
                .map(declaration -> getText(declaration, sourceBytes).strip())
                .map(declaration -> StringUtils.removeStart(declaration, PACKAGE_KEYWORD))
                .map(declaration -> StringUtils.removeEnd(declaration, STATEMENT_END).strip())
                .orElse("");
    }

    private String getScope(final TSNode chunkNode, final byte[] sourceBytes, final String packageName) {
        final List<String> typeNames = new ArrayList<>();

        for (TSNode node = chunkNode; isPresent(node); node = node.getParent()) {
            if (TYPE_DECLARATIONS.contains(node.getType())) {
                getName(node, sourceBytes).ifPresent(typeNames::add);
            }
        }

        Collections.reverse(typeNames);
        if (!packageName.isEmpty()) {
            typeNames.add(0, packageName);
        }
        return String.join(".", typeNames);
    }

    private String getLeadingComment(final TSNode chunkNode, final byte[] sourceBytes) {
        final TSNode previous = chunkNode.getPrevSibling();

        return isPresent(previous) && COMMENTS.contains(previous.getType()) ?
                getText(previous, sourceBytes) + "\n" : "";
    }

    private String getClassOutline(final TSNode classNode, final byte[] sourceBytes) {
        final TSNode body = getClassBody(classNode);

        if (!isPresent(body)) {
            return getText(classNode, sourceBytes);
        }

        return Stream.of(getSignature(classNode, body, sourceBytes), getFields(body, sourceBytes),
                        getMethodNames(body, sourceBytes))
                .filter(StringUtils::isNotEmpty)
                .collect(Collectors.joining("\n"));
    }

    private String getSignature(final TSNode classNode, final TSNode body, final byte[] sourceBytes) {
        return new String(sourceBytes, classNode.getStartByte(),
                body.getStartByte() - classNode.getStartByte(), StandardCharsets.UTF_8).strip();
    }

    private String getFields(final TSNode body, final byte[] sourceBytes) {
        final List<String> fields = getNamedChildren(body, FIELD_DECLARATIONS).stream()
                .map(field -> getText(field, sourceBytes).strip())
                .toList();

        return fields.isEmpty() ? "" : FIELDS_LABEL + String.join("\n", fields);
    }

    private String getMethodNames(final TSNode body, final byte[] sourceBytes) {
        final List<String> methodNames = getNamedChildren(body, METHOD_DECLARATIONS).stream()
                .map(method -> getName(method, sourceBytes))
                .flatMap(Optional::stream)
                .distinct()
                .toList();

        return methodNames.isEmpty() ? "" : METHODS_LABEL + String.join(", ", methodNames);
    }

    private TSNode getClassBody(final TSNode classNode) {
        final TSNode body = classNode.getChildByFieldName("body");

        return isPresent(body) ? body : getNamedChild(classNode, Set.of(CLASS_BODY));
    }

    private Optional<String> getName(final TSNode node, final byte[] sourceBytes) {
        final TSNode name = node.getChildByFieldName("name");

        return Optional.ofNullable(isPresent(name) ? name : getNamedChild(node, NAMES))
                .map(nameNode -> getText(nameNode, sourceBytes));
    }

    private TSNode getNamedChild(final TSNode node, final Set<String> types) {
        return getNamedChildren(node, types).stream().findFirst().orElse(null);
    }

    private List<TSNode> getNamedChildren(final TSNode node, final Set<String> types) {
        return IntStream.range(0, node.getNamedChildCount())
                .mapToObj(node::getNamedChild)
                .filter(child -> types.contains(child.getType()))
                .toList();
    }

    private String getText(final TSNode node, final byte[] sourceBytes) {
        return new String(sourceBytes, node.getStartByte(),
                node.getEndByte() - node.getStartByte(), StandardCharsets.UTF_8);
    }

    private boolean isPresent(final TSNode node) {
        return node != null && !node.isNull();
    }

    @Builder
    @Getter
    private static class CodeOutline {

        private String text;
        private String scope;
    }
}
