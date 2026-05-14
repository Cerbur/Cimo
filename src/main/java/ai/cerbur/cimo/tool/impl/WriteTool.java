package ai.cerbur.cimo.tool.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * 在工作区内写入 UTF-8 文本文件的工具，用于让 Agent 显式创建新文件或覆盖已有文本文件。
 *
 * <p>覆盖和创建父目录都必须由参数显式开启，避免路径拼写错误或误覆盖在模型调用中静默扩散。
 */
@Component
public class WriteTool implements Tool {

    private final FileToolSecurity security;
    private final ObjectNode schema;

    public WriteTool(FileToolSecurity security) {
        this.security = security;
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "write";
    }

    @Override
    public String getDescription() {
        return "Write a UTF-8 text file inside the workspace. Requires explicit overwrite for existing files, "
                + "explicit createParentDirectories for missing parents, rejects sensitive paths, binary targets, "
                + "workspace escapes, and content larger than 1MB.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    /**
     * 按工作区安全边界写入文本内容，并返回覆盖状态、创建的父目录和最终字节数。
     */
    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        String userPath = arguments.path("path").asText("");
        if (!arguments.has("content") || arguments.get("content").isNull()) {
            return new ToolResult(false, "", "Content is required.", null);
        }
        String content = arguments.path("content").asText();
        boolean overwrite = arguments.path("overwrite").asBoolean(false);
        boolean createParentDirectories = arguments.path("createParentDirectories").asBoolean(false);
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        try {
            Path path = security.resolveWorkspacePath(context, userPath);
            security.validateWritableTextPath(path, bytes, overwrite);
            boolean overwritten = Files.exists(path);
            List<Path> createdParents = ensureParentDirectory(path, createParentDirectories);

            Files.write(path, bytes);
            long size = Files.size(path);
            return new ToolResult(true, formatOutput(path, overwritten, createdParents, size), "", null);
        }
        catch (FileToolSecurityException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", "Failed to write file: " + ex.getMessage(), null);
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "Workspace-relative path to write.");
        propertiesNode.set("path", path);

        ObjectNode content = objectMapper.createObjectNode();
        content.put("type", "string");
        content.put("description", "UTF-8 text content to write. Maximum encoded size is 1MB.");
        propertiesNode.set("content", content);

        ObjectNode overwrite = objectMapper.createObjectNode();
        overwrite.put("type", "boolean");
        overwrite.put("description", "Whether an existing text file may be overwritten. Defaults to false.");
        propertiesNode.set("overwrite", overwrite);

        ObjectNode createParentDirectories = objectMapper.createObjectNode();
        createParentDirectories.put("type", "boolean");
        createParentDirectories.put("description", "Whether missing parent directories may be created. Defaults to false.");
        propertiesNode.set("createParentDirectories", createParentDirectories);

        root.set("properties", propertiesNode);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("path");
        required.add("content");
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    private List<Path> ensureParentDirectory(Path path, boolean createParentDirectories) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            return List.of();
        }
        if (Files.exists(parent)) {
            if (!Files.isDirectory(parent)) {
                throw new FileToolSecurityException("Parent path is not a directory for write: " + parent);
            }
            return List.of();
        }
        if (!createParentDirectories) {
            throw new FileToolSecurityException("Parent directory does not exist and createParentDirectories is false: "
                    + parent);
        }

        List<Path> createdParents = missingDirectories(parent);
        Files.createDirectories(parent);
        return createdParents;
    }

    private List<Path> missingDirectories(Path parent) {
        List<Path> reversed = new ArrayList<>();
        Path current = parent;
        while (current != null && !Files.exists(current)) {
            reversed.add(current);
            current = current.getParent();
        }

        List<Path> createdParents = new ArrayList<>();
        for (int i = reversed.size() - 1; i >= 0; i--) {
            createdParents.add(reversed.get(i));
        }
        return createdParents;
    }

    private String formatOutput(Path path, boolean overwritten, List<Path> createdParents, long sizeBytes) {
        return "path: " + path + System.lineSeparator()
                + "overwritten: " + overwritten + System.lineSeparator()
                + "createdParentDirectories: " + createdParents.size() + System.lineSeparator()
                + formatCreatedParents(createdParents)
                + "sizeBytes: " + sizeBytes;
    }

    private String formatCreatedParents(List<Path> createdParents) {
        if (createdParents.isEmpty()) {
            return "createdParents:" + System.lineSeparator();
        }
        StringBuilder builder = new StringBuilder("createdParents:").append(System.lineSeparator());
        for (Path parent : createdParents) {
            builder.append(parent).append(System.lineSeparator());
        }
        return builder.toString();
    }
}
