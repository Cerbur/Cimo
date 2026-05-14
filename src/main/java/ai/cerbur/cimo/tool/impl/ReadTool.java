package ai.cerbur.cimo.tool.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * 读取工作区内 UTF-8 文本文件的工具，用于让 Agent 在修改前理解现有代码和配置。
 *
 * <p>该工具只承担读取职责；路径边界、敏感文件、二进制文件和大小上限统一交给
 * {@link FileToolSecurity}，避免文件类工具各自漂移出不同的安全语义。
 */
@Component
public class ReadTool implements Tool {

    private final FileToolSecurity security;
    private final ObjectNode schema;

    public ReadTool(FileToolSecurity security) {
        this.security = security;
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "read";
    }

    @Override
    public String getDescription() {
        return "Read a UTF-8 text file inside the workspace. Rejects directories, binary files, sensitive paths, "
                + "workspace escapes, and files larger than 200KB.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    /**
     * 按工作区安全边界读取文本文件，并返回路径、字节数和完整内容。
     */
    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        String userPath = arguments.path("path").asText("");
        try {
            Path path = security.resolveWorkspacePath(context, userPath);
            security.validateReadableTextFile(path);
            String content = Files.readString(path, StandardCharsets.UTF_8);
            long size = Files.size(path);
            return new ToolResult(true, formatOutput(path, size, content), "", null);
        }
        catch (FileToolSecurityException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", "Failed to read file: " + ex.getMessage(), null);
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "Workspace-relative path to a UTF-8 text file.");
        propertiesNode.set("path", path);

        root.set("properties", propertiesNode);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("path");
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    private String formatOutput(Path path, long size, String content) {
        return "path: " + path + System.lineSeparator()
                + "sizeBytes: " + size + System.lineSeparator()
                + "content:" + System.lineSeparator()
                + content;
    }
}
