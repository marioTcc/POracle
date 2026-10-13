package com.mtcc.project.extractor.serviceimpl;

import com.mtcc.common.entity.InformationChunk;
import com.mtcc.common.entity.InformationType;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.config.TreeSitterConfig;
import com.mtcc.project.extractor.entity.Project;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
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

    private static final String PACKAGE = "org.springframework.security.web.method.annotation";

    @TempDir
    private Path tempDir;

    private final ChunkingProperties chunkingProperties = new ChunkingProperties();

    private final CodeChunkingServiceImpl codeChunkingService = new CodeChunkingServiceImpl(
            new TreeSitterConfig().tsConfig(), new IOServiceImpl(null, null), chunkingProperties);

    private final Project project = Project.builder()
            .projectName("TestProject")
            .projectPath(Paths.get("src/test/resources"))
            .build();

    @BeforeEach
    void setUp() {
        setMaxChars(5000);
    }

    private void setMaxChars(final int maxChars) {
        ReflectionTestUtils.setField(chunkingProperties, "fileOutlineMaxChars", maxChars);
    }

    private List<InformationChunk> chunk(final Path filePath) {
        return codeChunkingService.chunkFile(project, filePath, InformationType.CODE)
                .collectList()
                .block();
    }

    private InformationChunk outlineOfMock(final String fileName) {
        final List<InformationChunk> chunks = chunk(Paths.get("src/test/resources/mocks").resolve(fileName));

        assertEquals(1, chunks.size(), "A file must be outlined as one chunk");
        return chunks.get(0);
    }

    @Test
    void chunkFile_ShouldOutlineAJavaClassWithItsJavadocAnnotationsFieldsAndMethodNames() {
        final InformationChunk outline = outlineOfMock("ComplexTest.java");

        assertTrue(outline.getText().startsWith("In " + PACKAGE + "\n/**\n * This is a Javadoc comment for the class."),
                outline.getText());
        assertTrue(outline.getText().contains("@SuppressWarnings(\"rawtypes\")"), "Should keep the class annotations");
        assertTrue(outline.getText().contains("implements HandlerMethodArgumentResolver"), "Should keep the class signature");
        assertTrue(outline.getText().contains("""
                Fields:
                public static final String DEFAULT_PRINCIPAL_ATTRIBUTE = "principal";
                private boolean errorOnInvalidType = false;
                Methods: resolveArgument, supportsParameter"""), "Should list the fields with their values, then the methods");
        assertFalse(outline.getText().contains("int x = 10;"), "Should not contain method bodies");
        assertFalse(outline.getText().contains("public Object resolveArgument"), "Should not repeat the methods of the class");
    }

    @Test
    void chunkFile_ShouldDescribeTheOutline() {
        final InformationChunk outline = outlineOfMock("ComplexTest.java");

        assertEquals("TestProject", outline.getProjectName());
        assertEquals("mocks/ComplexTest.java", outline.getFilePath());
        assertEquals("ComplexTest.java", outline.getFilename());
        assertEquals("JAVA", outline.getLanguage());
        assertEquals(InformationType.CODE, outline.getInformationType());
        assertEquals(PACKAGE, outline.getContext());
        assertEquals("code-outline", outline.getChunkingStrategy());
    }

    @Test
    void chunkFile_ShouldCutAnOutlineLongerThanTheLimitAtTheEndOfALine() {
        setMaxChars(200);

        final String outline = outlineOfMock("ComplexTest.java").getText();

        assertTrue(outline.length() <= 204, outline);
        assertTrue(outline.endsWith("\n..."), outline);
    }

    @Test
    void chunkFile_ShouldKeepShortPythonDeclarationsWhole() {
        final InformationChunk outline = outlineOfMock("Test.py");

        assertEquals("""
                def test_function():
                    print("Hello function")

                class TestClass:
                    def test_method(self):
                        print("Hello method")""", outline.getText());
        assertEquals("Test.py", outline.getContext());
        assertEquals("PY", outline.getLanguage());
    }

    @Test
    void chunkFile_ShouldKeepOnlyTheFirstLineOfALongDeclaration() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("jobs.py"),
                "def run_all_jobs(queue):\n" + "    queue.run_the_next_job_and_wait_for_it()\n".repeat(10));

        assertEquals(List.of("def run_all_jobs(queue):"), chunk(file).stream().map(InformationChunk::getText).toList());
    }

    @Test
    void chunkFile_ShouldOutlineKotlinClassesObjectsAndTopLevelDeclarations() {
        final String outline = outlineOfMock("Test.kt").getText();

        assertTrue(outline.startsWith("const val DEFAULT_CURRENCY = \"EUR\""), outline);
        assertTrue(outline.contains("""
                In com.example.orders
                /**
                 * Computes the price of an order.
                 */
                class OrderService(private val repository: OrderRepository) : PricingService
                Fields:
                private val discounts = mutableMapOf<String, BigDecimal>()
                Methods: priceOf, discountFor"""), outline);
        assertTrue(outline.contains("""
                In com.example.orders.OrderService
                companion object
                Fields:
                const val MAX_DISCOUNT_PERCENT = 30
                Methods: create"""), outline);
        assertTrue(outline.contains("SHIPPED"), "Should keep the enum entries");
        assertTrue(outline.contains("object OrderDefaults\nMethods: currency"), outline);
        assertTrue(outline.contains("fun String.toOrderId(): String"), outline);
        assertFalse(outline.contains("repository.findById"), "Should not contain method bodies");
    }

    @Test
    void chunkFile_ShouldOutlineJavaScriptFunctionsClassesAndTopLevelStatements() {
        final String outline = outlineOfMock("Test.js").getText();

        assertTrue(outline.contains("// Fetches the orders of a customer.\n"), "Should keep the leading comment");
        assertTrue(outline.contains("class OrderView\nFields:\nstatic separator = ', '\nMethods: constructor, render"), outline);
        assertTrue(outline.contains("const DEFAULT_TIMEOUT"), outline);
        assertTrue(outline.contains("document.addEventListener"), outline);
        assertFalse(outline.contains("\nrender(orders)"), "Should not repeat the methods of the class");
    }

    @Test
    void chunkFile_ShouldOutlineTypeScriptTypesClassesAndFunctions() {
        final String outline = outlineOfMock("Test.ts").getText();

        assertTrue(outline.contains("interface Order {\n    id: string;\n    total: number;\n}"), outline);
        assertTrue(outline.contains("type OrderFilter"), outline);
        assertTrue(outline.contains("Shipped = 'SHIPPED'"), outline);
        assertTrue(outline.contains("abstract class OrderSource"), outline);
        assertTrue(outline.contains("class OrderClient extends OrderSource\nFields:\nprivate readonly retries = 3\nMethods: constructor, load"),
                outline);
        assertTrue(outline.contains("function totalOf"), outline);
    }

    @Test
    void chunkFile_ShouldOutlineEachSqlStatement() {
        final String outline = outlineOfMock("Test.sql").getText();

        assertTrue(outline.startsWith("-- Orders placed by the customers.\nCREATE TABLE orders"), outline);
        assertTrue(outline.contains("CREATE INDEX idx_orders_customer"), outline);
        assertTrue(outline.contains("INSERT INTO orders"), outline);
    }

    @Test
    void chunkFile_ShouldOutlineHtmlElements() {
        final InformationChunk outline = outlineOfMock("Test.html");

        assertTrue(outline.getText().contains("<h1>Hello</h1>"), outline.getText());
        assertEquals(1, StringUtils.countMatches(outline.getText(), "<h1>Hello</h1>"), "Should not repeat nested elements");
        assertEquals("HTML", outline.getLanguage());
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
    void chunkFile_ShouldStillOutlineTheParsedDeclarations_WhenTheSourceHasSyntaxErrors() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("Broken.java"), """
                class Broken {
                    void works() { int x = 10; }
                    void broken( {
                """);

        final List<InformationChunk> chunks = chunk(file);

        assertEquals(1, chunks.size());
        assertTrue(chunks.get(0).getText().contains("works"), chunks.get(0).getText());
    }

    @Test
    void chunkFile_ShouldFail_WhenTheFileCannotBeRead() {
        final RuntimeException error = assertThrows(RuntimeException.class, () -> chunk(tempDir.resolve("Missing.java")));

        assertInstanceOf(NoSuchFileException.class, Exceptions.unwrap(error));
    }
}
