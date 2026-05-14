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

class ListToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ListTool tool = new ListTool(new FileToolSecurity());

    @TempDir
    Path workspace;

    @Test
    void exposesListToolSchema() {
        JsonNode schema = tool.getParameterSchema();

        assertThat(tool.getName()).isEqualTo("list");
        assertThat(tool.getDescription()).contains("List directory");
        assertThat(schema.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("recursive").path("type").asText()).isEqualTo("boolean");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("path");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void listsDirectDirectoryEntriesInStableOrder() throws IOException {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.writeString(workspace.resolve("README.md"), "hello", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("."));

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isEmpty();
        assertThat(result.exitCode()).isNull();
        assertThat(result.output()).contains("path: " + workspace.toRealPath());
        assertThat(result.output()).contains("recursive: false");
        assertThat(result.output()).contains("entriesShown: 2");
        assertThat(result.output()).contains("[file] README.md (5 bytes)");
        assertThat(result.output()).contains("[dir] src/");
        assertThat(result.output()).doesNotContain("App.java");
        assertThat(result.output().indexOf("README.md")).isLessThan(result.output().indexOf("src/"));
    }

    @Test
    void listsRecursiveEntriesWithLimitedDepth() throws IOException {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src/main/java/Deep.java"), "class Deep {}", StandardCharsets.UTF_8);

        ObjectNode arguments = arguments(".");
        arguments.put("recursive", true);
        arguments.put("maxDepth", 2);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("recursive: true");
        assertThat(result.output()).contains("maxDepth: 2");
        assertThat(result.output()).contains("[dir] src/");
        assertThat(result.output()).contains("[file] src/App.java");
        assertThat(result.output()).contains("[dir] src/main/");
        assertThat(result.output()).doesNotContain("Deep.java");
    }

    @Test
    void rejectsWorkspaceEscape() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("../outside"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("outside workspace");
    }

    @Test
    void rejectsFilePathMisuse() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "hello", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("README.md"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("not a directory");
    }

    @Test
    void rejectsMissingPathArgument() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), objectMapper.createObjectNode());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Path is required");
    }

    @Test
    void limitsEntryCountAndReportsOmittedEntries() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "a", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("b.txt"), "b", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("c.txt"), "c", StandardCharsets.UTF_8);

        ObjectNode arguments = arguments(".");
        arguments.put("maxEntries", 2);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("entriesShown: 2");
        assertThat(result.output()).contains("entriesOmittedByLimit: 1");
        assertThat(result.output()).contains("[file] a.txt");
        assertThat(result.output()).contains("[file] b.txt");
        assertThat(result.output()).doesNotContain("[file] c.txt");
    }

    @Test
    void filtersSensitiveEntriesWithoutRevealingNames() throws IOException {
        Files.writeString(workspace.resolve(".env"), "TOKEN=value", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("safe.txt"), "safe", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("."));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("entriesShown: 1");
        assertThat(result.output()).contains("entriesFilteredSensitive: 1");
        assertThat(result.output()).contains("safe.txt");
        assertThat(result.output()).doesNotContain(".env");
    }

    @Test
    void filtersSymlinkEscapingWorkspaceWithoutFollowingIt() throws IOException {
        Path outside = Files.createTempDirectory("cimo-list-outside");
        Path outsideFile = Files.writeString(outside.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
        Files.createSymbolicLink(workspace.resolve("linked-secret.txt"), outsideFile);
        Files.writeString(workspace.resolve("safe.txt"), "safe", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("."));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("entriesShown: 1");
        assertThat(result.output()).contains("entriesFilteredSensitive: 1");
        assertThat(result.output()).contains("safe.txt");
        assertThat(result.output()).doesNotContain("linked-secret");
        assertThat(result.output()).doesNotContain("secret.txt");
    }

    @Test
    void doesNotDescendIntoSensitiveDirectoriesWhenRecursive() throws IOException {
        Files.createDirectories(workspace.resolve(".git/objects"));
        Files.writeString(workspace.resolve(".git/config"), "secret", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve(".git/objects/token-file"), "secret", StandardCharsets.UTF_8);
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/App.java"), "class App {}", StandardCharsets.UTF_8);

        ObjectNode arguments = arguments(".");
        arguments.put("recursive", true);
        arguments.put("maxDepth", 3);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("entriesFilteredSensitive: 1");
        assertThat(result.output()).contains("[dir] src/");
        assertThat(result.output()).contains("[file] src/App.java");
        assertThat(result.output()).doesNotContain(".git");
        assertThat(result.output()).doesNotContain("token-file");
    }

    @Test
    void clampsRecursiveDepthToHardLimit() throws IOException {
        Files.createDirectories(workspace.resolve("a/b/c/d/e/f/g"));
        Files.writeString(workspace.resolve("a/b/c/d/e/f/g/TooDeep.java"), "class TooDeep {}", StandardCharsets.UTF_8);

        ObjectNode arguments = arguments(".");
        arguments.put("recursive", true);
        arguments.put("maxDepth", 99);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("maxDepth: 6");
        assertThat(result.output()).contains("[dir] a/");
        assertThat(result.output()).contains("[dir] a/b/");
        assertThat(result.output()).contains("[dir] a/b/c/");
        assertThat(result.output()).contains("[dir] a/b/c/d/");
        assertThat(result.output()).contains("[dir] a/b/c/d/e/");
        assertThat(result.output()).contains("[dir] a/b/c/d/e/f/");
        assertThat(result.output()).doesNotContain("a/b/c/d/e/f/g/");
        assertThat(result.output()).doesNotContain("TooDeep.java");
    }

    private ObjectNode arguments(String path) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", path);
        return arguments;
    }
}
