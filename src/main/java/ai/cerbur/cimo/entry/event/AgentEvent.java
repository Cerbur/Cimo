package ai.cerbur.cimo.entry.event;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * AgentLoop 向入口层发出的事件模型，入口层只负责展示或转发，不反向理解 LLM 协议。
 */
public sealed interface AgentEvent {
    /**
     * 当前只是用户可见的简短进度提示，不承载可持久化状态。
     */
    record Thinking(String message) implements AgentEvent {
    }

    /**
     * 工具调用展示事件；args 保留结构化参数，便于 debug 模式打印原始协议。
     */
    record ToolCall(String toolName, JsonNode args) implements AgentEvent {
    }

    /**
     * 工具结果展示事件，只携带入口层需要展示的内容。
     */
    record ToolResult(String toolName, String result) implements AgentEvent {
    }

    /**
     * 模型文本响应事件，content 可以是流式增量而不是完整回复。
     */
    record Response(String content) implements AgentEvent {
    }

    record Error(String message) implements AgentEvent {
    }
}
