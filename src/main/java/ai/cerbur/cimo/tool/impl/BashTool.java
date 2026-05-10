package ai.cerbur.cimo.tool.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolResult;

/**
 * Step 1 的受限 Bash 工具，只允许执行结构化的 echo 调用，用于跑通最小 tool_use 链路。
 */
@Component
public class BashTool implements Tool {

    private static final String ALLOWED_COMMAND = "echo";

    private final int timeoutSeconds;
    private final ObjectNode schema;

    public BashTool(@Value("${cimo.tool.bash.timeout-seconds:30}") int timeoutSeconds) {
        this.timeoutSeconds = normalizeTimeoutSeconds(timeoutSeconds);
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "Run a very small allowlisted bash command. Step 1 only supports echo.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    @Override
    public ToolResult execute(JsonNode arguments) {
        String command = arguments.path("command").asText("");
        if (!ALLOWED_COMMAND.equals(command)) {
            return new ToolResult(false, "", "Command is not allowed: " + command, null);
        }

        // 使用 ProcessBuilder 参数数组传参，不接收整条 shell 字符串，降低 Step 1 的命令注入面。
        List<String> processCommand = new ArrayList<>();
        processCommand.add(ALLOWED_COMMAND);
        JsonNode args = arguments.path("args");
        if (args.isArray()) {
            args.forEach(arg -> processCommand.add(arg.asText()));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return new ToolResult(false, "", "Command timed out.", null);
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String error = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            return new ToolResult(exitCode == 0, output.stripTrailing(), error.stripTrailing(), exitCode);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, "", "Command interrupted.", null);
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        // schema 与 ALLOWED_COMMAND 保持同源，避免模型看到的能力大于实际白名单。
        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        ArrayNode commandEnum = objectMapper.createArrayNode();
        commandEnum.add(ALLOWED_COMMAND);
        command.set("enum", commandEnum);
        command.put("description", "The command to run. Step 1 only allows echo.");
        propertiesNode.set("command", command);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("type", "array");
        ObjectNode item = objectMapper.createObjectNode();
        item.put("type", "string");
        args.set("items", item);
        args.put("description", "Arguments passed to echo.");
        propertiesNode.set("args", args);

        root.set("properties", propertiesNode);
        ArrayNode required = objectMapper.createArrayNode();
        required.add("command");
        required.add("args");
        root.set("required", required);
        root.put("additionalProperties", false);
        return root;
    }

    private static int normalizeTimeoutSeconds(int timeoutSeconds) {
        return timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }
}
