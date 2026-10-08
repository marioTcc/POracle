package com.mtcc.project.extractor.serviceimpl;

import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.mtcc.oracle.entity.Dataset;
import com.mtcc.project.extractor.config.ChunkingProperties;
import com.mtcc.project.extractor.config.IOProperties;
import com.mtcc.project.extractor.exception.InvalidIngestionSourceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import reactor.core.Exceptions;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(SpringExtension.class)
class IOServiceImplTest {

    @TempDir
    private Path tempDir;
    @Mock
    private IOProperties ioProperties;
    @Mock
    private ChunkingProperties chunkingProperties;
    @InjectMocks
    private IOServiceImpl ioService;

    private void setupMocks(boolean ignoreHidden, Set<String> supportedExtensions) {
        when(ioProperties.getIgnoreHiddenFilesAndFolders()).thenReturn(ignoreHidden);
        when(chunkingProperties.getSupportedFileExtensions()).thenReturn(supportedExtensions);
    }

    @Test
    void getAllFiles_ShouldReturnAllSupportedFiles_WhenHiddenFilesNotIgnored() throws IOException {
        final Path file1 = Files.createFile(tempDir.resolve("file1.txt"));
        final Path hiddenDir = Files.createDirectory(tempDir.resolve(".hiddenDir"));
        final Path fileInHiddenDir = Files.createFile(hiddenDir.resolve("inner.txt"));

        setupMocks(false, Set.of("txt"));

        final List<Path> output = ioService.getAllFiles(tempDir.toString())
                .collectList()
                .block();

        assertNotNull(output);
        assertEquals(2, output.size());
        assertTrue(output.containsAll(List.of(file1, fileInHiddenDir)));
    }

    @Test
    void getAllFiles_ShouldFilterHiddenFiles_WhenIgnoreHiddenEnabled() throws IOException {
        final Path file1 = Files.createFile(tempDir.resolve("file1.txt"));
        final Path dotFile = Files.createFile(tempDir.resolve(".dotfile.txt"));
        final Path hiddenDir = Files.createDirectory(tempDir.resolve(".hiddenDir"));
        final Path fileInHiddenDir = Files.createFile(hiddenDir.resolve("inner.txt"));

        setupMocks(true, Set.of("txt"));

        final List<Path> output = ioService.getAllFiles(tempDir.toString())
                .collectList()
                .block();

        assertNotNull(output);
        assertTrue(output.contains(file1));
        assertFalse(output.contains(fileInHiddenDir));
        assertFalse(output.contains(dotFile));
    }

    @Test
    void getAllFiles_ShouldFilterFilesWithoutExtension() throws IOException {
        final Path file1 = Files.createFile(tempDir.resolve("file1.txt"));
        final Path noExtFile = Files.createFile(tempDir.resolve("noext"));

        setupMocks(false, Set.of("txt"));

        final List<Path> output = ioService.getAllFiles(tempDir.toString())
                .collectList()
                .block();

        assertNotNull(output);
        assertTrue(output.contains(file1));
        assertFalse(output.contains(noExtFile));
    }

    @Test
    void getAllFiles_ShouldReturnOnlySupportedExtensions() throws IOException {
        final Path javaFile = Files.createFile(tempDir.resolve("Main.java"));
        final Path docFile = Files.createFile(tempDir.resolve("readme.md"));
        final Path configFile = Files.createFile(tempDir.resolve("application.yml"));
        final Path serializedFile = Files.createFile(tempDir.resolve("org.example.Request.serialized"));
        final Path keystoreFile = Files.createFile(tempDir.resolve("spring.keystore"));
        final Path imageFile = Files.createFile(tempDir.resolve("logo.png"));

        setupMocks(false, Set.of("java", "md", "yml"));

        final List<Path> output = ioService.getAllFiles(tempDir.toString())
                .collectList()
                .block();

        assertNotNull(output);
        assertEquals(3, output.size());
        assertTrue(output.containsAll(List.of(javaFile, docFile, configFile)));
        assertFalse(output.contains(serializedFile));
        assertFalse(output.contains(keystoreFile));
        assertFalse(output.contains(imageFile));
    }

    @Test
    void getAllFiles_ShouldMatchExtensionsIgnoringCase() throws IOException {
        final Path upperCaseDoc = Files.createFile(tempDir.resolve("README.MD"));
        final Path mixedCaseCode = Files.createFile(tempDir.resolve("Main.Java"));
        final Path upperCaseImage = Files.createFile(tempDir.resolve("LOGO.PNG"));

        setupMocks(false, Set.of("java", "md"));

        final List<Path> output = ioService.getAllFiles(tempDir.toString())
                .collectList()
                .block();

        assertNotNull(output);
        assertEquals(2, output.size());
        assertTrue(output.containsAll(List.of(upperCaseDoc, mixedCaseCode)));
        assertFalse(output.contains(upperCaseImage));
    }

    @Test
    void createFolder_ShouldCreateAnEmptyProjectFolderUnderTheDownloadFolder() {
        when(ioProperties.getProjectsDownloadFolder()).thenReturn(tempDir.toString());

        final Path projectFolder = ioService.createFolder("spring-ai").block();

        assertEquals(tempDir.resolve("spring-ai"), projectFolder);
        assertTrue(Files.isDirectory(projectFolder));
    }

    @Test
    void createFolder_ShouldReplaceAnExistingProjectFolder_IncludingReadOnlyFiles() throws IOException {
        final Path existingFolder = Files.createDirectories(tempDir.resolve("spring-ai").resolve(".git"));
        final Path readOnlyFile = Files.createFile(existingFolder.resolve("pack-1.pack"));
        assertTrue(readOnlyFile.toFile().setReadOnly());
        when(ioProperties.getProjectsDownloadFolder()).thenReturn(tempDir.toString());

        final Path projectFolder = ioService.createFolder("spring-ai").block();

        assertTrue(Files.isDirectory(projectFolder));
        assertFalse(Files.exists(readOnlyFile));
    }

    @Test
    void createFolder_ShouldRejectProjectNamesThatLeaveTheDownloadFolder() throws IOException {
        final Path downloadFolder = Files.createDirectory(tempDir.resolve("projects"));
        final Path siblingFile = Files.createFile(tempDir.resolve("keep-me.txt"));
        when(ioProperties.getProjectsDownloadFolder()).thenReturn(downloadFolder.toString());

        for (final String projectName : List.of("..", ".", "", "../other", "nested/../..")) {
            assertThrows(InvalidIngestionSourceException.class, () -> ioService.createFolder(projectName).block(),
                    "Should reject the project name: '" + projectName + "'");
        }

        assertTrue(Files.exists(siblingFile), "Nothing outside the project folder may be deleted");
        assertTrue(Files.isDirectory(downloadFolder), "The download folder itself may not be deleted");
    }

    @Test
    void getExistingFolder_ShouldReturnTheNormalizedAbsolutePathOfAnExistingFolder() throws IOException {
        final Path folder = Files.createDirectory(tempDir.resolve("my-project"));

        final Path resolved = ioService.getExistingFolder(folder.resolve("..").resolve("my-project").toString()).block();

        assertEquals(folder.toAbsolutePath().normalize(), resolved);
    }

    @Test
    void getExistingFolder_ShouldRejectAMissingFolderOrAFile() throws IOException {
        final Path file = Files.createFile(tempDir.resolve("notes.txt"));

        assertThrows(InvalidIngestionSourceException.class,
                () -> ioService.getExistingFolder(tempDir.resolve("missing").toString()).block());
        assertThrows(InvalidIngestionSourceException.class, () -> ioService.getExistingFolder(file.toString()).block());
    }

    @Test
    void readJsonResource_ShouldReadTheDatasetConfiguredInTheApplicationProperties() throws IOException {
        final Properties applicationProperties = new Properties();
        try (InputStream properties = getClass().getResourceAsStream("/application.properties")) {
            applicationProperties.load(properties);
        }

        final Dataset dataset = ioService
                .readJsonResource(applicationProperties.getProperty("EVALUATION.DEFAULT.DATASET"), Dataset.class).block();

        assertEquals("spring-ai-security-golden-dataset-v2", dataset.getDatasetName());
        assertFalse(dataset.getRecords().isEmpty());
        assertTrue(dataset.getRecords().stream().allMatch(datasetRecord ->
                datasetRecord.getQuestion() != null && datasetRecord.getAnswer() != null));
    }

    @Test
    void readJsonResource_ShouldFail_WhenTheResourceDoesNotExist() {
        final RuntimeException error = assertThrows(RuntimeException.class,
                () -> ioService.readJsonResource("datasets/missing.json", Dataset.class).block());

        assertInstanceOf(FileNotFoundException.class, Exceptions.unwrap(error));
    }

    @Test
    void readJsonResource_ShouldFail_WhenTheResourceHasAnUnknownField() {
        final RuntimeException error = assertThrows(RuntimeException.class,
                () -> ioService.readJsonResource("datasets/dataset-with-unknown-field.json", Dataset.class).block());

        assertInstanceOf(UnrecognizedPropertyException.class, Exceptions.unwrap(error));
    }

    @Test
    void readText_ShouldReturnTheContentOfTheFile() throws IOException {
        final Path file = Files.writeString(tempDir.resolve("README.md"), "# Sample project");

        assertEquals("# Sample project", ioService.readText(file).block());
    }

    @Test
    void readText_ShouldFallbackToIso88591_WhenTheFileIsNotValidUtf8() throws IOException {
        final String text = "Credenciais inv\u00e1lidas: n\u00e3o foi poss\u00edvel autenticar";
        final Path file = Files.write(tempDir.resolve("messages_pt_PT.properties"), text.getBytes(StandardCharsets.ISO_8859_1));

        assertEquals(text, ioService.readText(file).block());
    }
}
