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

import org.springframework.stereotype.Component;

import ai.cerbur.cimo.config.CimoProperties;
import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolResult;

@Component
public class BashTool implements Tool {

    private final CimoProperties.Bash properties;
    private final ObjectNode schema;

    public BashTool(CimoProperties properties) {
        this.properties = properties.tool().bash();
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
        if (!properties.allowedCommands().contains(command)) {
            return new ToolResult(false, "", "Command is not allowed: " + command, null);
        }
        if (!"echo".equals(command)) {
            return new ToolResult(false, "", "Step 1 only supports echo.", null);
        }

        List<String> processCommand = new ArrayList<>();
        processCommand.add("echo");
        JsonNode args = arguments.path("args");
        if (args.isArray()) {
            args.forEach(arg -> processCommand.add(arg.asText()));
        }

        ProcessBuilder processBuilder = new ProcessBuilder(processCommand);
        try {
            Process process = processBuilder.start();
            boolean completed = process.waitFor(properties.timeoutSeconds(), TimeUnit.SECONDS);
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

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        ArrayNode commandEnum = objectMapper.createArrayNode();
        commandEnum.add("echo");
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
}
