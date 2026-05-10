package ai.cerbur.cimo.client.model;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 消息内容块模型，用于保留 Anthropic 风格的 text / tool_use / tool_result 结构化语义。
 */
public sealed interface ContentBlock {
    /**
     * 普通文本片段，可以和工具调用出现在同一个 assistant turn 中。
     */
    record Text(String text) implements ContentBlock {
    }

    /**
     * 模型请求执行工具的结构化描述，id 必须在后续 ToolResult 中原样回传。
     */
    record ToolUse(String id, String name, JsonNode input) implements ContentBlock {
    }

    /**
     * 工具执行结果块，作为 user/tool response 消息回传给模型。
     */
    record ToolResult(String toolUseId, String content, boolean isError) implements ContentBlock {
    }
}
