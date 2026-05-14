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

class ReadToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReadTool tool = new ReadTool(new FileToolSecurity());

    @TempDir
    Path workspace;

    @Test
    void exposesReadToolSchema() {
        JsonNode schema = tool.getParameterSchema();

        assertThat(tool.getName()).isEqualTo("read");
        assertThat(tool.getDescription()).contains("Read");
        assertThat(schema.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("path");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void readsWorkspaceTextFileWithPathSizeAndContent() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Hello.java"), "class Hello {}\n", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("src/Hello.java"));

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isEmpty();
        assertThat(result.exitCode()).isNull();
        assertThat(result.output()).contains("path: " + workspace.resolve("src/Hello.java").toRealPath());
        assertThat(result.output()).contains("sizeBytes: 15");
        assertThat(result.output()).contains("content:\nclass Hello {}\n");
    }

    @Test
    void rejectsWorkspaceEscape() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("../outside.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("outside workspace");
    }

    @Test
    void rejectsDirectory() throws IOException {
        Files.createDirectories(workspace.resolve("src"));

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("src"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Directory is not allowed");
    }

    @Test
    void rejectsSensitivePath() throws IOException {
        Files.writeString(workspace.resolve(".env"), "TOKEN=value", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments(".env"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Sensitive path");
    }

    @Test
    void rejectsBinaryFile() throws IOException {
        Files.write(workspace.resolve("data.txt"), new byte[] {65, 0, 66});

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("data.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Binary file");
    }

    @Test
    void rejectsFileOverReadLimit() throws IOException {
        Files.write(workspace.resolve("large.txt"), new byte[(int) FileToolSecurity.READ_MAX_BYTES + 1]);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("large.txt"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceeds 204800 bytes");
    }

    @Test
    void rejectsMissingPathArgument() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), objectMapper.createObjectNode());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Path is required");
    }

    private ObjectNode arguments(String path) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        return arguments;
    }
}
