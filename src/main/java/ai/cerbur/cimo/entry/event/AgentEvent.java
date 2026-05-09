package ai.cerbur.cimo.entry.event;

import com.fasterxml.jackson.databind.JsonNode;

public sealed interface AgentEvent {
    record Thinking(String message) implements AgentEvent {
    }

    record ToolCall(String toolName, JsonNode args) implements AgentEvent {
    }

    record ToolResult(String toolName, String result) implements AgentEvent {
    }

    record Response(String content) implements AgentEvent {
    }

    record Error(String message) implements AgentEvent {
    }

    record StatusChange(AgentState state) implements AgentEvent {
    }
}
