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

class EditToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final EditTool tool = new EditTool(new FileToolSecurity());

    @TempDir
    Path workspace;

    @Test
    void exposesEditToolSchema() {
        JsonNode schema = tool.getParameterSchema();

        assertThat(tool.getName()).isEqualTo("edit");
        assertThat(tool.getDescription()).contains("Edit");
        assertThat(schema.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("oldString").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("newString").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("required")).extracting(JsonNode::asText)
                .containsExactly("path", "oldString", "newString");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void replacesUniqueOldStringAndReportsSizeChange() throws IOException {
        Path target = workspace.resolve("src/App.java");
        String originalContent = "class App {\n    String value = \"old\";\n}\n";
        String editedContent = "class App {\n    String value = \"newer\";\n}\n";
        Files.createDirectories(target.getParent());
        Files.writeString(target, originalContent, StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("src/App.java", "String value = \"old\";", "String value = \"newer\";"));

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isEmpty();
        assertThat(result.exitCode()).isNull();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo(editedContent);
        assertThat(result.output()).contains("path: " + target.toRealPath());
        assertThat(result.output()).contains("replacements: 1");
        assertThat(result.output()).contains("sizeBeforeBytes: " + originalContent.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.output()).contains("sizeAfterBytes: " + editedContent.getBytes(StandardCharsets.UTF_8).length);
        assertThat(result.output()).contains("sizeDeltaBytes: 2");
    }

    @Test
    void rejectsMissingOldStringWithoutChangingFile() throws IOException {
        Path target = Files.writeString(workspace.resolve("note.txt"), "hello", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("note.txt", "missing", "changed"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("oldString not found");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("hello");
    }

    @Test
    void rejectsAmbiguousOldStringWithoutChangingFile() throws IOException {
        Path target = Files.writeString(workspace.resolve("note.txt"), "same\nsame\n", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("note.txt", "same", "changed"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("oldString matched 2 times, provide more surrounding context");
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("same\nsame\n");
    }

    @Test
    void rejectsWorkspaceEscape() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("../outside.txt", "old", "new"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("outside workspace");
    }

    @Test
    void rejectsSensitivePath() throws IOException {
        Files.writeString(workspace.resolve(".env"), "TOKEN=value", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments(".env", "TOKEN", "VALUE"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Sensitive path");
    }

    @Test
    void rejectsBinaryFile() throws IOException {
        Files.write(workspace.resolve("data.txt"), new byte[] {65, 0, 66});

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("data.txt", "A", "B"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Binary file");
    }

    @Test
    void rejectsFileOverEditLimit() throws IOException {
        Files.write(workspace.resolve("large.txt"), new byte[(int) FileToolSecurity.EDIT_MAX_BYTES + 1]);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("large.txt", "old", "new"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("exceeds 1048576 bytes");
    }

    @Test
    void rejectsMissingPathArgument() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("oldString", "old");
        arguments.put("newString", "new");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Path is required");
    }

    @Test
    void rejectsMissingOldStringArgument() {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "note.txt");
        arguments.put("newString", "new");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("oldString is required");
    }

    @Test
    void allowsEmptyReplacement() throws IOException {
        Path target = Files.writeString(workspace.resolve("note.txt"), "remove me", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace),
                arguments("note.txt", " me", ""));

        assertThat(result.success()).isTrue();
        assertThat(Files.readString(target, StandardCharsets.UTF_8)).isEqualTo("remove");
    }

    private ObjectNode arguments(String path, String oldString, String newString) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        arguments.put("oldString", oldString);
        arguments.put("newString", newString);
        return arguments;
    }
}
