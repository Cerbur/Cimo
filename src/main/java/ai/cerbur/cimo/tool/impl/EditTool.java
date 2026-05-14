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
 * 对工作区内已有 UTF-8 文本文件执行唯一字符串替换的编辑工具。
 *
 * <p>该工具只支持明确的 oldString -> newString 局部替换；找不到或匹配多处都会失败，
 * 迫使 Agent 提供更精确上下文，避免模糊编辑误伤用户文件。
 */
@Component
public class EditTool implements Tool {

    private final FileToolSecurity security;
    private final ObjectNode schema;

    public EditTool(FileToolSecurity security) {
        this.security = security;
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "edit";
    }

    @Override
    public String getDescription() {
        return "Edit an existing UTF-8 text file inside the workspace by replacing exactly one oldString match "
                + "with newString. Rejects missing, ambiguous, sensitive, binary, oversized, or escaping paths.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    /**
     * 校验目标文件后执行唯一匹配替换，并返回替换次数和文件大小变化。
     */
    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        String userPath = arguments.path("path").asText("");
        String oldString = arguments.path("oldString").asText("");
        String newString = arguments.path("newString").asText("");

        try {
            validateEditStrings(oldString, arguments.has("newString"));
            Path path = security.resolveWorkspacePath(context, userPath);
            security.validateEditableTextFile(path);

            String content = Files.readString(path, StandardCharsets.UTF_8);
            int matches = countMatches(content, oldString);
            if (matches == 0) {
                return new ToolResult(false, "", "oldString not found: " + path, null);
            }
            if (matches > 1) {
                return new ToolResult(false, "",
                        "oldString matched " + matches + " times, provide more surrounding context: " + path, null);
            }

            long beforeSize = Files.size(path);
            String edited = content.replace(oldString, newString);
            Files.writeString(path, edited, StandardCharsets.UTF_8);
            long afterSize = Files.size(path);
            return new ToolResult(true, formatOutput(path, beforeSize, afterSize), "", null);
        }
        catch (FileToolSecurityException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", "Failed to edit file: " + ex.getMessage(), null);
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode path = objectMapper.createObjectNode();
        path.put("type", "string");
        path.put("description", "Workspace-relative path to an existing UTF-8 text file.");
        propertiesNode.set("path", path);

        ObjectNode oldString = objectMapper.createObjectNode();
        oldString.put("type", "string");
        oldString.put("description", "Exact text to replace. It must appear exactly once in the target file.");
        propertiesNode.set("oldString", oldString);

        ObjectNode newString = objectMapper.createObjectNode();
        newString.put("type", "string");
        newString.put("description", "Replacement text.");
        propertiesNode.set("newString", newString);

        root.set("properties", propertiesNode);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("path");
        required.add("oldString");
        required.add("newString");
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    private void validateEditStrings(String oldString, boolean hasNewString) {
        if (oldString.isEmpty()) {
            throw new FileToolSecurityException("oldString is required.");
        }
        if (!hasNewString) {
            throw new FileToolSecurityException("newString is required.");
        }
    }

    private int countMatches(String content, String oldString) {
        int count = 0;
        int index = 0;
        while ((index = content.indexOf(oldString, index)) >= 0) {
            count++;
            index += oldString.length();
        }
        return count;
    }

    private String formatOutput(Path path, long beforeSize, long afterSize) {
        return "path: " + path + System.lineSeparator()
                + "replacements: 1" + System.lineSeparator()
                + "sizeBeforeBytes: " + beforeSize + System.lineSeparator()
                + "sizeAfterBytes: " + afterSize + System.lineSeparator()
                + "sizeDeltaBytes: " + (afterSize - beforeSize);
    }
}
