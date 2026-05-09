package ai.cerbur.cimo.tool;

import com.fasterxml.jackson.databind.JsonNode;

public interface Tool {
    String getName();

    String getDescription();

    JsonNode getParameterSchema();

    ToolResult execute(JsonNode arguments);
}
