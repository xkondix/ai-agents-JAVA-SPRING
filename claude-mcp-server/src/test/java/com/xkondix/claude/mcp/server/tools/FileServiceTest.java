package com.xkondix.claude.mcp.server.tools;

import com.xkondix.claude.mcp.server.config.ClaudeMcpProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sandbox behaviour of the file layer.
 *
 * This server hands an LLM the ability to overwrite and delete files in a real
 * repository, with no human approval step (impossible over STDIO). The only
 * thing standing between a confused model and the rest of the disk is
 * resolveAndValidate() plus the extension allow-list — so those two deserve
 * tests far more than the happy path does.
 *
 * No Spring context: ClaudeMcpProperties is a record, so it can just be
 * constructed. That is a practical dividend of the record-based
 * @ConfigurationProperties pattern — the configuration is testable without
 * booting anything.
 */
@DisplayName("FileService sandbox")
class FileServiceTest {

    @TempDir
    Path root;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        fileService = new FileService(new ClaudeMcpProperties(
                root.toString(),
                List.of(".java", ".yml", ".md"),
                List.of("target", ".git")));
    }

    @Test
    @DisplayName("rejects a path that escapes the project root")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> fileService.readFile("../../etc/passwd.md"))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Path traversal detected");
    }

    @Test
    @DisplayName("rejects an absolute path pointing outside the sandbox")
    void rejectsAbsoluteEscape() {
        assertThatThrownBy(() -> fileService.readFile("/tmp/elsewhere.md"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("refuses to read a file type outside the allow-list")
    void refusesDisallowedExtension() throws IOException {
        Files.writeString(root.resolve("payload.exe"), "binary");

        assertThat(fileService.readFile("payload.exe"))
                .startsWith("ERROR: File type not allowed");
    }

    @Test
    @DisplayName("refuses to write a file type outside the allow-list")
    void refusesDisallowedExtensionOnWrite() throws IOException {
        assertThat(fileService.writeFile("payload.exe", "x"))
                .startsWith("ERROR: File type not allowed");

        assertThat(Files.exists(root.resolve("payload.exe")))
                .as("a rejected write must not touch the disk")
                .isFalse();
    }

    @Test
    @DisplayName("write then read returns exactly what was written")
    void writeThenReadRoundTrip() throws IOException {
        assertThat(fileService.writeFile("notes.md", "hello world"))
                .startsWith("OK:");

        assertThat(fileService.readFile("notes.md")).isEqualTo("hello world");
    }

    @Test
    @DisplayName("create_file refuses to clobber an existing file")
    void createFileRefusesToOverwrite() throws IOException {
        fileService.createFile("notes.md", "first");

        assertThat(fileService.createFile("notes.md", "second"))
                .startsWith("ERROR: File already exists");

        assertThat(fileService.readFile("notes.md")).isEqualTo("first");
    }

    @Test
    @DisplayName("reading a missing file returns readable text, not an exception")
    void missingFileReturnsErrorText() throws IOException {
        assertThat(fileService.readFile("nope.md"))
                .startsWith("ERROR: File not found");
    }

    @Test
    @DisplayName("an empty path lists the project root — the 'Contents of: null' regression")
    void emptyPathListsProjectRoot() throws IOException {
        Files.writeString(root.resolve("a.md"), "a");
        Files.createDirectory(root.resolve("sub"));

        String listing = fileService.listFiles("");

        assertThat(listing).contains("[FILE] a.md").contains("[DIR]  sub");
    }

    @Test
    @DisplayName("the tree walk skips ignored directories")
    void projectStructureSkipsIgnoredDirs() throws IOException {
        Files.createDirectory(root.resolve("target"));
        Files.writeString(root.resolve("target").resolve("Generated.java"), "x");
        Files.writeString(root.resolve("Real.java"), "x");

        String tree = fileService.getProjectStructure(4);

        assertThat(tree).contains("Real.java").doesNotContain("Generated.java");
    }

    @Test
    @DisplayName("search reports the matching file and line number")
    void searchFindsMatchesWithLineNumbers() throws IOException {
        Files.writeString(root.resolve("Sample.java"), "class A {\n  // TODO fix\n}\n");

        String result = fileService.searchInFiles("todo", ".java");

        assertThat(result).contains("Sample.java").contains("L2").contains("1 file(s) matched");
    }

    @Test
    @DisplayName("move_file refuses a directory and points at the right tool")
    void moveFileRefusesDirectories() throws IOException {
        Files.createDirectory(root.resolve("pkg"));

        assertThat(fileService.moveFile("pkg", "pkg2"))
                .isEqualTo("ERROR: Use move_directory for directories.");
    }
}
