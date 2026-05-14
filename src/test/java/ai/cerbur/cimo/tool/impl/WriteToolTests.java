package ai.cerbur.cimo.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.cerbur.cimo.tool.ToolExecutionContext;
import ai.cerbur.cimo.tool.ToolResult;
import ai.cerbur.cimo.tool.file.FileToolSecurity;

class WriteToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WriteTool tool = new WriteTool(new FileToolSecurity());

    @TempDir
    Path workspace;

    @Test
    void exposesWriteToolSchema() {
        JsonNode schema = tool.getParameterSchema();

        assertThat(tool.getName()).isEqualTo("write");
        assertThat(tool.getDescription()).contains("Write");
        assertThat(schema.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("content").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("overwrite").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("properties").path("createParentDirectories").path("type").asText())
                .isEqualTo("boolean");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("path", "content");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void writesNewWorkspaceTextFile() throws IOException {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("hello.txt", "hello\n"));

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isEmpty();
        assertThat(result.exitCode()).isNull();
        assertThat(Files.readString(workspace.resolve("hello.txt"), StandardCharsets.UTF_8)).isEqualTo("hello\n");
        assertThat(result.output()).contains("path: " + workspace.resolve("hello.txt").toRealPath());
        assertThat(result.output()).contains("overwritten: false");
        assertThat(result.output()).contains("createdParentDirectories: 0");
        assertThat(result.output()).contains("sizeBytes: 6");
    }

    @Test
    void rejectsExistingFileWithoutExplicitOverwrite() throws IOException {
        Files.writeString(workspace.resolve("hello.txt"), "old", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("hello.txt", "new"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("overwrite is false");
        assertThat(Files.readString(workspace.resolve("hello.txt"), StandardCharsets.UTF_8)).isEqualTo("old");
    }

    @Test
    void overwritesExistingTextFileWhenExplicit() throws IOException {
        Files.writeString(workspace.resolve("hello.txt"), "old", StandardCharsets.UTF_8);
        ObjectNode arguments = arguments("hello.txt", "new");
        arguments.put("overwrite", true);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(workspace.resolve("hello.txt"), StandardCharsets.UTF_8)).isEqualTo("new");
        assertThat(result.output()).contains("overwritten: true");
        assertThat(result.output()).contains("sizeBytes: 3");
    }

    @Test
    void rejectsMissingParentDirectoryByDefault() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("src/main/App.java", "class App {}"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("createParentDirectories is false");
        assertThat(workspace.resolve("src")).doesNotExist();
    }

    @Test
    void createsParentDirectoriesWhenExplicit() throws IOException {
        ObjectNode arguments = arguments("src/main/App.java", "class App {}\n");
        arguments.put("createParentDirectories", true);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(workspace.resolve("src/main/App.java"), StandardCharsets.UTF_8))
                .isEqualTo("class App {}\n");
        assertThat(result.output()).contains("createdParentDirectories: 2");
        assertThat(result.output()).contains(workspace.resolve("src").toRealPath().toString());
        assertThat(result.output()).contains(workspace.resolve("src/main").toRealPath().toString());
    }

    @Test
    void rejectsWorkspaceEscape() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("../outside.txt", "nope"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("outside workspace");
    }

    @Test
    void rejectsMissingTargetUnderSymlinkEscapingWorkspace() throws IOException {
        Path outside = Files.createTempDirectory("cimo-write-outside");
        Files.createSymbolicLink(workspace.resolve("linked-dir"), outside);
        ObjectNode arguments = arguments("linked-dir/new/file.txt", "nope");
        arguments.put("createParentDirectories", true);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("symlink");
        assertThat(outside.resolve("new")).doesNotExist();
    }

    @Test
    void rejectsSensitivePath() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments(".env.local", "TOKEN=value"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Sensitive path");
    }

    @Test
    void rejectsBinaryTargetExtension() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("image.png", "text"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Binary target");
    }

    @Test
    void rejectsOverwriteOfBinaryExistingFile() throws IOException {
        Files.write(workspace.resolve("data.txt"), new byte[] {65, 0, 66});
        ObjectNode arguments = arguments("data.txt", "text");
        arguments.put("overwrite", true);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Binary file");
        assertThat(Files.readAllBytes(workspace.resolve("data.txt"))).containsExactly(65, 0, 66);
    }

    @Test
    void rejectsContentOverWriteLimit() {
        String largeContent = "x".repeat((int) FileToolSecurity.WRITE_MAX_BYTES + 1);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("large.txt", largeContent));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceeds 1048576 bytes");
        assertThat(workspace.resolve("large.txt")).doesNotExist();
    }

    @Test
    void rejectsMissingPathArgument() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("content", "hello");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Path is required");
    }

    @Test
    void rejectsMissingContentArgument() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "hello.txt");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Content is required");
        assertThat(workspace.resolve("hello.txt")).doesNotExist();
    }

    private ObjectNode arguments(String path, String content) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        arguments.put("content", content);
        return arguments;
    }
}
