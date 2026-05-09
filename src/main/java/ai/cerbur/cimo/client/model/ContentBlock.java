package ai.cerbur.cimo.client.model;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface ContentBlock {
    record Text(String text) implements ContentBlock {
    }

    record ToolUse(String id, String name, JsonNode input) implements ContentBlock {
    }

    record ToolResult(String toolUseId, String content, boolean isError) implements ContentBlock {
    }
}
