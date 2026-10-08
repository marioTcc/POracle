package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.TreeSitterConfig;
import com.mtcc.project.extractor.entity.Project;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Exceptions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisabledOnOs(value = OS.WINDOWS, architectures = "aarch64",
        disabledReason = "the tree-sitter jars do not ship a native library for Windows on ARM64")
class CodeChunkingServiceImplTest {

    private static final String CLASS_SCOPE =
            "org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver";

    @TempDir
    private Path tempDir;

    private final CodeChunkingServiceImpl codeChunkingService =
            new CodeChunkingServiceImpl(new TreeSitterConfig().tsConfig(), new IOServiceImpl(null, null));

    private final Project project = Project.builder()
            .projectName("TestProject")
            .projectPath(Paths.get("src/test/resources"))
            .build();

    private List<InformationChunk> chunk(final Path filePath) {
        return codeChunkingService.chunkFile(project, filePath, InformationType.CODE)
                .collectList()
                .block();
    }

    private List<InformationChunk> chunkMock(final String fileName) {
        return chunk(Paths.get("src/test/resources/mocks").resolve(fileName));
    }

    private List<InformationChunk> chunkJavaMock() {
        return chunkMock("ComplexTest.java");
    }

    private InformationChunk chunkContaining(final List<InformationChunk> chunks, final String text) {
        return chunks.stream()
                .filter(chunk -> chunk.getText().contains(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No chunk contains: " + text));
    }

    private InformationChunk chunkStartingWith(final List<InformationChunk> chunks, final String text) {
        return chunks.stream()
                .filter(chunk -> chunk.getText().startsWith(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No chunk starts with: " + text));
    }

    @Test
    void chunkFile_ShouldEmitClassOutlineWithJavadocAnnotationsFieldsAndMethodNames() {
        final List<InformationChunk> chunks = chunkJavaMock();
        final InformationChunk classChunk = chunkContaining(chunks, "public final class AuthenticationPrincipalArgumentResolver");

        assertTrue(classChunk.getText().contains("This is a Javadoc comment for the class."), "Should keep the class Javadoc");
        assertTrue(classChunk.getText().contains("@SuppressWarnings(\"rawtypes\")"), "Should keep the class annotations");
        assertTrue(classChunk.getText().contains("implements HandlerMethodArgumentResolver"), "Should keep the class signature");
        assertTrue(classChunk.getText().contains("""
                Fields:
                public static final String DEFAULT_PRINCIPAL_ATTRIBUTE = "principal";
                private boolean errorOnInvalidType = false;
                Methods: resolveArgument, supportsParameter"""), "Should list the fields with their values, then the methods");
        assertFalse(classChunk.getText().contains("int x = 10;"), "Should not contain method bodies");
        assertEquals(CLASS_SCOPE, classChunk.getContext());
        assertEquals(1, chunks.stream().filter(chunk -> chunk.getText().contains("DEFAULT_PRINCIPAL_ATTRIBUTE")).count(),
                "Should not emit the fields on their own");
    }

    @Test
    void chunkFile_ShouldEmitMethodsWithJavadocAndEnclosingClass() {
        final InformationChunk methodChunk = chunkContaining(chunkJavaMock(), "public Object resolveArgument");

        assertTrue(methodChunk.getText().contains("This is a Javadoc comment for the resolveArgument method."),
                "Should keep the method Javadoc");
        assertTrue(methodChunk.getText().contains("int x = 10;"), "Should contain the method body");
        assertEquals(CLASS_SCOPE, methodChunk.getContext());
        assertEquals("mocks/ComplexTest.java", methodChunk.getFilePath());
        assertEquals("ComplexTest.java", methodChunk.getFilename());
        assertEquals("JAVA", methodChunk.getLanguage());
    }

    @Test
    void chunkFile_ShouldEmitPythonFunctionsClassesAndMethods() {
        final List<InformationChunk> chunks = chunkMock("Test.py");

        assertEquals(3, chunks.size());
        assertEquals("Test.py", chunkStartingWith(chunks, "def test_function").getContext());
        assertTrue(chunkStartingWith(chunks, "class TestClass").getText().contains("def test_method"));
        assertEquals("TestClass", chunkStartingWith(chunks, "def test_method").getContext());
        assertEquals("PY", chunks.get(0).getLanguage());
    }

    @Test
    void chunkFile_ShouldEmitHtmlElementsScriptsAndStyles() {
        final List<InformationChunk> chunks = chunkMock("Test.html");

        assertEquals("<h1>Hello</h1>", chunkStartingWith(chunks, "<h1>").getText());
        assertEquals("<script>console.log('test');</script>", chunkStartingWith(chunks, "<script>").getText());
        assertEquals("<style>body { color: red; }</style>", chunkStartingWith(chunks, "<style>").getText());
        assertEquals("HTML", chunks.get(0).getLanguage());
    }

    @Test
    void chunkFile_ShouldEmitKotlinClassOutlinesMembersAndTopLevelDeclarations() {
        final List<InformationChunk> chunks = chunkMock("Test.kt");

        final InformationChunk classChunk = chunkContaining(chunks, "class OrderService(");
        assertTrue(classChunk.getText().contains("Computes the price of an order."), "Should keep the class KDoc");
        assertTrue(classChunk.getText().contains(": PricingService"), "Should keep the class signature");
        assertTrue(classChunk.getText().contains("""
                Fields:
                private val discounts = mutableMapOf<String, BigDecimal>()
                Methods: priceOf, discountFor"""), "Should list the properties with their values, then the methods");
        assertFalse(classChunk.getText().contains("repository.findById"), "Should not contain method bodies");
        assertEquals("com.example.orders.OrderService", classChunk.getContext());

        final InformationChunk methodChunk = chunkStartingWith(chunks, "override fun priceOf");
        assertTrue(methodChunk.getText().contains("repository.findById"), "Should contain the method body");
        assertEquals("com.example.orders.OrderService", methodChunk.getContext());

        final InformationChunk companionChunk = chunkStartingWith(chunks, "companion object");
        assertEquals("companion object\nFields:\nconst val MAX_DISCOUNT_PERCENT = 30\nMethods: create", companionChunk.getText());
        assertEquals("com.example.orders.OrderService", companionChunk.getContext());
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.getText().startsWith("private val discounts")),
                "Should not emit the properties of a class on their own");
        assertTrue(chunkStartingWith(chunks, "enum class OrderStatus").getText().contains("SHIPPED"), "Should keep the enum entries");
        assertEquals("com.example.orders.OrderDefaults", chunkStartingWith(chunks, "fun currency").getContext());
        assertEquals("Test.kt", chunkStartingWith(chunks, "fun String.toOrderId").getContext());
        assertEquals("Test.kt", chunkStartingWith(chunks, "const val DEFAULT_CURRENCY").getContext());
        assertTrue(chunks.stream().noneMatch(chunk -> chunk.getText().startsWith("val order =")),
                "Should not emit local variables");
    }

    @Test
    void chunkFile_ShouldEmitJavaScriptFunctionsClassesAndTopLevelStatements() {
        final List<InformationChunk> chunks = chunkMock("Test.js");

        assertTrue(chunkContaining(chunks, "function fetchOrders").getText().startsWith("// Fetches the orders of a customer."),
                "Should keep the leading comment");
        assertEquals("class OrderView\nFields:\nstatic separator = ', '\nMethods: constructor, render",
                chunkStartingWith(chunks, "class OrderView").getText());
        assertEquals("OrderView", chunkStartingWith(chunks, "render(orders)").getContext());
        chunkStartingWith(chunks, "const DEFAULT_TIMEOUT");
        chunkStartingWith(chunks, "const formatPrice");
        chunkStartingWith(chunks, "{ fetchOrders, OrderView }");
        chunkStartingWith(chunks, "document.addEventListener");
        assertEquals("JS", chunks.get(0).getLanguage());
    }

    @Test
    void chunkFile_ShouldEmitTypeScriptTypesClassesAndFunctions() {
        final List<InformationChunk> chunks = chunkMock("Test.ts");

        assertTrue(chunkStartingWith(chunks, "interface Order").getText().contains("total: number"));
        chunkStartingWith(chunks, "type OrderFilter");
        assertTrue(chunkStartingWith(chunks, "enum OrderStatus").getText().contains("Shipped"));
        assertEquals("abstract class OrderSource", chunkStartingWith(chunks, "abstract class OrderSource").getText());
        assertEquals("class OrderClient extends OrderSource\nFields:\nprivate readonly retries = 3\nMethods: constructor, load",
                chunkStartingWith(chunks, "class OrderClient").getText());
        assertEquals("OrderClient", chunkStartingWith(chunks, "async load()").getContext());
        chunkStartingWith(chunks, "function totalOf");
    }

    @Test
    void chunkFile_ShouldEmitOneChunkPerSqlStatement() {
        final List<InformationChunk> chunks = chunkMock("Test.sql");

        assertEquals(3, chunks.size());
        assertTrue(chunkContaining(chunks, "CREATE TABLE orders").getText().startsWith("-- Orders placed by the customers."),
                "Should keep the leading comment");
        chunkStartingWith(chunks, "CREATE INDEX idx_orders_customer");
        chunkStartingWith(chunks, "INSERT INTO orders");
    }

    @Test
    void chunkFile_ShouldEmitNothing_WhenTheLanguageIsNotConfigured() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("main.go"), "package main\n\nfunc main() {}\n");

        assertTrue(chunk(file).isEmpty());
    }

    @Test
    void chunkFile_ShouldEmitNothing_WhenTheFileHasNoDeclarations() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("package-info.java"), "package sample;\n");

        assertTrue(chunk(file).isEmpty());
    }

    @Test
    void chunkFile_ShouldStillEmitTheParsedDeclarations_WhenTheSourceHasSyntaxErrors() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("Broken.java"), """
                class Broken {
                    void works() { int x = 10; }
                    void broken( {
                """);

        final List<InformationChunk> chunks = chunk(file);

        assertTrue(chunkStartingWith(chunks, "void works()").getText().contains("int x = 10;"));
    }

    @Test
    void chunkFile_ShouldFail_WhenTheFileCannotBeRead() {
        final RuntimeException error = assertThrows(RuntimeException.class, () -> chunk(tempDir.resolve("Missing.java")));

        assertInstanceOf(NoSuchFileException.class, Exceptions.unwrap(error));
    }
}
