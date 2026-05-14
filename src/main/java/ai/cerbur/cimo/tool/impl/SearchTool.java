package ai.cerbur.cimo.tool.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.regex.PatternSyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.stereotype.Component;

import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolExecutionContext;
import ai.cerbur.cimo.tool.ToolResult;
import ai.cerbur.cimo.tool.file.FileToolSecurity;
import ai.cerbur.cimo.tool.file.FileToolSecurityException;

/**
 * 在工作区内搜索路径或文本内容的工具，用于让 Agent 快速定位待读取和待修改的文件。
 *
 * <p>该工具不包装外部 rg/grep，而是复用文件工具共享安全边界自行遍历。路径搜索承担 Step 2
 * 暂不独立提供 glob 工具的最小职责，内容搜索则按稳定顺序返回可截断的匹配片段。
 */
@Component
public class SearchTool implements Tool {

    private static final int DEFAULT_MAX_RESULTS = FileToolSecurity.SEARCH_MAX_RESULTS;
    private static final int SNIPPET_MAX_CHARS = 1000;
    private static final long OUTPUT_METADATA_RESERVED_BYTES = 2048L;

    private final FileToolSecurity security;
    private final ObjectNode schema;

    public SearchTool(FileToolSecurity security) {
        this.security = security;
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "search";
    }

    @Override
    public String getDescription() {
        return "Search workspace files by content or by path/name. Supports substring matching and simple * / ? "
                + "glob patterns for path search, while filtering sensitive and binary files.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    /**
     * 执行受限搜索：先校验搜索起点，再按稳定路径顺序收集最多 100 条结果和 100KB 输出。
     */
    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        String query = arguments.path("query").asText("");
        String userPath = arguments.path("path").asText(".");
        String mode = arguments.path("mode").asText("content");
        int maxResults = clampPositiveInt(arguments.path("maxResults").asInt(DEFAULT_MAX_RESULTS),
                DEFAULT_MAX_RESULTS, FileToolSecurity.SEARCH_MAX_RESULTS);

        if (query.isBlank()) {
            return new ToolResult(false, "", "Query is required for search.", null);
        }
        if (!"content".equals(mode) && !"path".equals(mode)) {
            return new ToolResult(false, "", "Search mode must be content or path: " + mode, null);
        }

        try {
            Path root = security.resolveWorkspacePath(context, userPath);
            security.validateBrowsablePath(root);
            validateSearchRoot(root);

            SearchOutput output = new SearchOutput(maxResults,
                    FileToolSecurity.SEARCH_MAX_OUTPUT_BYTES - OUTPUT_METADATA_RESERVED_BYTES);
            List<Path> candidates = collectCandidates(context, root, output);
            for (Path candidate : candidates) {
                if (output.isOutputLimitReached()) {
                    break;
                }
                if ("path".equals(mode)) {
                    searchPath(root, candidate, query, output);
                }
                else {
                    searchContent(root, candidate, query, output);
                }
            }

            return new ToolResult(true, formatOutput(root, mode, query, output), "", null);
        }
        catch (FileToolSecurityException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (PatternSyntaxException ex) {
            return new ToolResult(false, "", "Invalid path search glob pattern: " + query, null);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", "Failed to search workspace: " + ex.getMessage(), null);
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode query = objectMapper.createObjectNode();
        query.put("type", "string");
        query.put("description", "Text to search for. In path mode, * and ? are treated as glob wildcards.");
        propertiesNode.set("query", query);

        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "Workspace-relative file or directory to search from. Defaults to '.'.");
        propertiesNode.set("path", path);

        ObjectNode mode = objectMapper.createObjectNode();
        mode.put("type", "string");
        mode.put("description", "Search mode. Defaults to content.");
        ArrayNode enumNode = objectMapper.createArrayNode();
        enumNode.add("content");
        enumNode.add("path");
        mode.set("enum", enumNode);
        propertiesNode.set("mode", mode);

        ObjectNode maxResults = objectMapper.createObjectNode();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum result count. Defaults to 100 and is capped at 100.");
        maxResults.put("minimum", 1);
        maxResults.put("maximum", FileToolSecurity.SEARCH_MAX_RESULTS);
        propertiesNode.set("maxResults", maxResults);

        root.set("properties", propertiesNode);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("query");
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    private void validateSearchRoot(Path root) {
        if (!Files.exists(root)) {
            throw new FileToolSecurityException("Search path does not exist: " + root);
        }
        if (!Files.isDirectory(root) && !Files.isRegularFile(root)) {
            throw new FileToolSecurityException("Search path must be a file or directory: " + root);
        }
    }

    private List<Path> collectCandidates(ToolExecutionContext context, Path root, SearchOutput output)
            throws IOException {
        if (Files.isRegularFile(root)) {
            return validateCandidate(context, root, output) ? List.of(root) : List.of();
        }
        try (var paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                    .filter(path -> validateCandidate(context, path, output))
                    .toList();
        }
    }

    private boolean validateCandidate(ToolExecutionContext context, Path path, SearchOutput output) {
        try {
            security.resolveWorkspacePath(context, path.toString());
            security.validateSearchableTextFile(path);
            return true;
        }
        catch (FileToolSecurityException ex) {
            output.filteredFiles++;
            return false;
        }
    }

    private void searchPath(Path root, Path candidate, String query, SearchOutput output) {
        String relativePath = displayPath(root, candidate);
        String name = candidate.getFileName().toString();
        if (matchesPathQuery(relativePath, name, query)) {
            output.append("path: " + relativePath);
        }
    }

    private boolean matchesPathQuery(String relativePath, String name, String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        String normalizedPath = relativePath.toLowerCase(Locale.ROOT);
        String normalizedName = name.toLowerCase(Locale.ROOT);
        if (!query.contains("*") && !query.contains("?")) {
            return normalizedPath.contains(normalizedQuery) || normalizedName.contains(normalizedQuery);
        }

        PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + query);
        return matcher.matches(Path.of(relativePath)) || matcher.matches(Path.of(name));
    }

    private void searchContent(Path root, Path candidate, String query, SearchOutput output) throws IOException {
        String relativePath = displayPath(root, candidate);
        try (BufferedReader reader = Files.newBufferedReader(candidate, StandardCharsets.UTF_8)) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.contains(query)) {
                    output.append("content: " + relativePath + ":" + lineNumber + ": " + snippet(line));
                    if (output.isOutputLimitReached()) {
                        return;
                    }
                }
            }
        }
    }

    private String displayPath(Path root, Path candidate) {
        if (root.equals(candidate)) {
            return candidate.getFileName().toString();
        }
        return root.relativize(candidate).toString();
    }

    private String snippet(String line) {
        String trimmed = line.strip();
        if (trimmed.length() <= SNIPPET_MAX_CHARS) {
            return trimmed;
        }
        return trimmed.substring(0, SNIPPET_MAX_CHARS) + "...[truncated]";
    }

    /**
     * 固定输出字段和截断计数，方便 Agent 区分“无结果”和“结果被限制”。
     */
    private String formatOutput(Path root, String mode, String query, SearchOutput output) {
        return "path: " + root + System.lineSeparator()
                + "mode: " + mode + System.lineSeparator()
                + "query: " + query + System.lineSeparator()
                + "resultsShown: " + output.resultsShown + System.lineSeparator()
                + "resultsOmittedByLimit: " + output.resultsOmittedByLimit + System.lineSeparator()
                + "outputTruncated: " + output.outputTruncated + System.lineSeparator()
                + "filesFiltered: " + output.filteredFiles + System.lineSeparator()
                + "results:" + System.lineSeparator()
                + output.resultLines;
    }

    private int clampPositiveInt(int value, int defaultValue, int hardMax) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, hardMax);
    }

    private static final class SearchOutput {

        private final int maxResults;
        private final long maxOutputBytes;
        private final StringBuilder resultLines = new StringBuilder();
        private int resultsShown;
        private int resultsOmittedByLimit;
        private int filteredFiles;
        private boolean outputTruncated;

        private SearchOutput(int maxResults, long maxOutputBytes) {
            this.maxResults = maxResults;
            this.maxOutputBytes = maxOutputBytes;
        }

        private void append(String line) {
            if (resultsShown >= maxResults) {
                resultsOmittedByLimit++;
                return;
            }
            String nextLine = line + System.lineSeparator();
            long nextBytes = resultLines.toString().getBytes(StandardCharsets.UTF_8).length
                    + nextLine.getBytes(StandardCharsets.UTF_8).length;
            if (nextBytes > maxOutputBytes) {
                outputTruncated = true;
                resultsOmittedByLimit++;
                return;
            }
            resultLines.append(nextLine);
            resultsShown++;
        }

        private boolean isOutputLimitReached() {
            return outputTruncated;
        }
    }
}
