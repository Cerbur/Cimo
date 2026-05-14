package ai.cerbur.cimo.tool.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

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
 * 列出工作区内目录内容的工具，用于让 Agent 在读取具体文件前先理解项目结构。
 *
 * <p>默认只列出直接子项；递归模式也会限制深度和数量，避免一次工具调用把大型仓库结构全部
 * 展开。路径边界和敏感路径判断复用 {@link FileToolSecurity}，输出只保留稳定、可读的结构信息。
 */
@Component
public class ListTool implements Tool {

    private static final int DEFAULT_MAX_ENTRIES = 100;
    private static final int HARD_MAX_ENTRIES = 500;
    private static final int DEFAULT_RECURSIVE_DEPTH = 2;
    private static final int HARD_MAX_DEPTH = 6;

    private final FileToolSecurity security;
    private final ObjectNode schema;

    public ListTool(FileToolSecurity security) {
        this.security = security;
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "list";
    }

    @Override
    public String getDescription() {
        return "List directory entries inside the workspace. Defaults to non-recursive output, rejects files and "
                + "workspace escapes, and caps recursive depth and entry count.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    /**
     * 按稳定顺序列出目录项；敏感路径会被过滤计数，不把敏感文件名暴露给模型。
     */
    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        String userPath = arguments.path("path").asText("");
        boolean recursive = arguments.path("recursive").asBoolean(false);
        int maxEntries = clampPositiveInt(arguments.path("maxEntries").asInt(DEFAULT_MAX_ENTRIES),
                DEFAULT_MAX_ENTRIES, HARD_MAX_ENTRIES);
        int maxDepth = recursive
                ? clampPositiveInt(arguments.path("maxDepth").asInt(DEFAULT_RECURSIVE_DEPTH),
                        DEFAULT_RECURSIVE_DEPTH, HARD_MAX_DEPTH)
                : 1;

        try {
            Path path = security.resolveWorkspacePath(context, userPath);
            security.validateBrowsablePath(path);
            validateDirectory(path);

            ListOutput output = new ListOutput(maxEntries);
            collectEntries(context, path, path, recursive, maxDepth, 1, output);

            return new ToolResult(true,
                    formatOutput(path, recursive, maxDepth, output),
                    "", null);
        }
        catch (FileToolSecurityException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", "Failed to list directory: " + ex.getMessage(), null);
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "Workspace-relative path to a directory.");
        propertiesNode.set("path", path);

        ObjectNode recursive = objectMapper.createObjectNode();
        recursive.put("type", "boolean");
        recursive.put("description", "Whether to list descendants. Defaults to false.");
        propertiesNode.set("recursive", recursive);

        ObjectNode maxDepth = objectMapper.createObjectNode();
        maxDepth.put("type", "integer");
        maxDepth.put("description", "Recursive depth limit. Defaults to 2 and is capped at 6.");
        maxDepth.put("minimum", 1);
        maxDepth.put("maximum", HARD_MAX_DEPTH);
        propertiesNode.set("maxDepth", maxDepth);

        ObjectNode maxEntries = objectMapper.createObjectNode();
        maxEntries.put("type", "integer");
        maxEntries.put("description", "Maximum number of entries to return. Defaults to 100 and is capped at 500.");
        maxEntries.put("minimum", 1);
        maxEntries.put("maximum", HARD_MAX_ENTRIES);
        propertiesNode.set("maxEntries", maxEntries);

        root.set("properties", propertiesNode);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("path");
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    private void validateDirectory(Path path) {
        if (!Files.exists(path)) {
            throw new FileToolSecurityException("Directory does not exist for list: " + path);
        }
        if (!Files.isDirectory(path)) {
            throw new FileToolSecurityException("Path is not a directory for list: " + path);
        }
    }

    private void collectEntries(ToolExecutionContext context, Path root, Path directory, boolean recursive,
            int maxDepth, int currentDepth, ListOutput output) throws IOException {
        List<Path> children;
        try (var paths = Files.list(directory)) {
            children = paths
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        for (Path child : children) {
            ListEntry entry = toEntry(child);
            if (!validateDiscoveredPath(context, child, output)) {
                continue;
            }

            if (!output.append(formatEntry(root, entry))) {
                continue;
            }

            if (recursive && currentDepth < maxDepth && "dir".equals(entry.type())) {
                collectEntries(context, root, child, true, maxDepth, currentDepth + 1, output);
            }
        }
    }

    private boolean validateDiscoveredPath(ToolExecutionContext context, Path path, ListOutput output) {
        try {
            security.resolveWorkspacePath(context, path.toString());
            security.validateBrowsablePath(path);
            return true;
        }
        catch (FileToolSecurityException ex) {
            output.filteredSensitive++;
            return false;
        }
    }

    private ListEntry toEntry(Path path) {
        String type;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            type = "dir";
        }
        else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            type = "file";
        }
        else {
            type = "other";
        }
        return new ListEntry(path, type);
    }

    private String formatEntry(Path root, ListEntry entry) throws IOException {
        Path relative = root.relativize(entry.path());
        if ("dir".equals(entry.type())) {
            return "[dir] " + relative + "/";
        }
        if ("file".equals(entry.type())) {
            return "[file] " + relative + " (" + Files.size(entry.path()) + " bytes)";
        }
        return "[other] " + relative;
    }

    /**
     * 保持输出为稳定的行式文本，便于模型扫描和测试断言。
     *
     * <p>示例：
     * <pre>
     * path: /workspace
     * recursive: false
     * maxDepth: 1
     * entriesShown: 2
     * entriesOmittedByLimit: 0
     * entriesFilteredSensitive: 1
     * entries:
     * [file] README.md (1295 bytes)
     * [dir] src/
     * </pre>
     */
    private String formatOutput(Path path, boolean recursive, int maxDepth, ListOutput output) {
        return "path: " + path + System.lineSeparator()
                + "recursive: " + recursive + System.lineSeparator()
                + "maxDepth: " + maxDepth + System.lineSeparator()
                + "entriesShown: " + output.entriesShown + System.lineSeparator()
                + "entriesOmittedByLimit: " + output.entriesOmittedByLimit + System.lineSeparator()
                + "entriesFilteredSensitive: " + output.filteredSensitive + System.lineSeparator()
                + "entries:" + System.lineSeparator()
                + output.entryLines;
    }

    private int clampPositiveInt(int value, int defaultValue, int hardMax) {
        if (value <= 0) {
            return defaultValue;
        }
        return Math.min(value, hardMax);
    }

    private record ListEntry(Path path, String type) {
    }

    private static final class ListOutput {

        private final int maxEntries;
        private final StringBuilder entryLines = new StringBuilder();
        private int entriesShown;
        private int entriesOmittedByLimit;
        private int filteredSensitive;

        private ListOutput(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        private boolean append(String line) {
            if (entriesShown >= maxEntries) {
                entriesOmittedByLimit++;
                return false;
            }
            entryLines.append(line).append(System.lineSeparator());
            entriesShown++;
            return true;
        }
    }
}
