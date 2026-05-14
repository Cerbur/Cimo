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

class SearchToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SearchTool tool = new SearchTool(new FileToolSecurity());

    @TempDir
    Path workspace;

    @Test
    void exposesSearchToolSchema() {
        JsonNode schema = tool.getParameterSchema();

        assertThat(tool.getName()).isEqualTo("search");
        assertThat(tool.getDescription()).contains("Search workspace");
        assertThat(schema.path("properties").path("query").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("path").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").path("mode").path("enum")).extracting(JsonNode::asText)
                .containsExactly("content", "path");
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("query");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void searchesFileContentInStableOrder() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("README.md"), "needle in docs\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src/App.java"), "class App {\n// needle\n}\n", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("needle"));

        assertThat(result.success()).isTrue();
        assertThat(result.error()).isEmpty();
        assertThat(result.exitCode()).isNull();
        assertThat(result.output()).contains("mode: content");
        assertThat(result.output()).contains("resultsShown: 2");
        assertThat(result.output()).contains("content: README.md:1: needle in docs");
        assertThat(result.output()).contains("content: src/App.java:2: // needle");
        assertThat(result.output().indexOf("README.md")).isLessThan(result.output().indexOf("src/App.java"));
    }

    @Test
    void searchesPathsBySubstringAndGlob() throws IOException {
        Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(workspace.resolve("src/main/java/AppController.java"), "class AppController {}",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("src/main/java/AppService.java"), "class AppService {}",
                StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("README.md"), "docs", StandardCharsets.UTF_8);

        ObjectNode substringArguments = arguments("controller");
        substringArguments.put("mode", "path");
        ToolResult substringResult = tool.execute(new ToolExecutionContext(workspace), substringArguments);

        assertThat(substringResult.success()).isTrue();
        assertThat(substringResult.output()).contains("path: src/main/java/AppController.java");
        assertThat(substringResult.output()).doesNotContain("AppService");

        ObjectNode globArguments = arguments("*.md");
        globArguments.put("mode", "path");
        ToolResult globResult = tool.execute(new ToolExecutionContext(workspace), globArguments);

        assertThat(globResult.success()).isTrue();
        assertThat(globResult.output()).contains("path: README.md");
        assertThat(globResult.output()).doesNotContain("AppController");
    }

    @Test
    void limitsResultCountAndReportsOmittedMatches() throws IOException {
        Files.writeString(workspace.resolve("a.txt"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("b.txt"), "needle\n", StandardCharsets.UTF_8);
        Files.writeString(workspace.resolve("c.txt"), "needle\n", StandardCharsets.UTF_8);
        ObjectNode arguments = arguments("needle");
        arguments.put("maxResults", 2);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("resultsShown: 2");
        assertThat(result.output()).contains("resultsOmittedByLimit: 1");
        assertThat(result.output()).contains("content: a.txt:1");
        assertThat(result.output()).contains("content: b.txt:1");
        assertThat(result.output()).doesNotContain("content: c.txt:1");
    }

    @Test
    void returnsEmptyResultMetadataWhenNoMatch() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "hello", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("absent"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("resultsShown: 0");
        assertThat(result.output()).contains("resultsOmittedByLimit: 0");
        assertThat(result.output()).contains("results:\n");
    }

    @Test
    void searchesSingleFilePathWithoutEmptyResultPath() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "needle", StandardCharsets.UTF_8);
        ObjectNode arguments = arguments("needle");
        arguments.put("path", "README.md");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("content: README.md:1: needle");
        assertThat(result.output()).doesNotContain("content: :1");
    }

    @Test
    void rejectsWorkspaceEscape() {
        ObjectNode arguments = arguments("needle");
        arguments.put("path", "../outside");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("outside workspace");
    }

    @Test
    void filtersSensitiveAndBinaryFilesWithoutRevealingSensitiveNames() throws IOException {
        Files.writeString(workspace.resolve(".env"), "needle=secret", StandardCharsets.UTF_8);
        Files.write(workspace.resolve("data.bin"), new byte[] {65, 0, 66});
        Files.writeString(workspace.resolve("safe.txt"), "needle safe", StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("needle"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("resultsShown: 1");
        assertThat(result.output()).contains("filesFiltered: 2");
        assertThat(result.output()).contains("safe.txt");
        assertThat(result.output()).doesNotContain(".env");
        assertThat(result.output()).doesNotContain("data.bin");
    }

    @Test
    void rejectsMissingQuery() {
        ToolResult result = tool.execute(new ToolExecutionContext(workspace), objectMapper.createObjectNode());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Query is required");
    }

    @Test
    void rejectsInvalidMode() {
        ObjectNode arguments = arguments("needle");
        arguments.put("mode", "regex");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("content or path");
    }

    @Test
    void rejectsInvalidGlobPattern() throws IOException {
        Files.writeString(workspace.resolve("README.md"), "hello", StandardCharsets.UTF_8);
        ObjectNode arguments = arguments("*\\");
        arguments.put("mode", "path");

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Invalid path search glob pattern");
    }

    @Test
    void truncatesLongMatchingLineSnippet() throws IOException {
        String longLine = "needle " + "x".repeat(1300);
        Files.writeString(workspace.resolve("long.txt"), longLine, StandardCharsets.UTF_8);

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("needle"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("...[truncated]");
        assertThat(result.output()).doesNotContain("x".repeat(1300));
    }

    @Test
    void truncatesTotalOutputNearSearchLimit() throws IOException {
        String longLine = "needle " + "x".repeat(1300);
        for (int index = 0; index < 120; index++) {
            Files.writeString(workspace.resolve(String.format("match-%03d.txt", index)), longLine,
                    StandardCharsets.UTF_8);
        }

        ToolResult result = tool.execute(new ToolExecutionContext(workspace), arguments("needle"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("outputTruncated: true");
        assertThat(result.output()).contains("resultsOmittedByLimit: 1");
        assertThat(result.output().getBytes(StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo((int) FileToolSecurity.SEARCH_MAX_OUTPUT_BYTES);
    }

    private ObjectNode arguments(String query) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("query", query);
        return arguments;
    }
}
