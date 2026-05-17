package ai.cerbur.cimo.client.anthropic;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.cerbur.cimo.client.model.StreamEvent;

/**
 * 将 Spring AI 的 AssistantMessage 流片段归一化为 Cimo 事件，重点保证文本事件始终是新增 delta。
 */
class SpringAiStreamEventAdapter {

    private final ObjectMapper objectMapper;
    private final StringBuilder emittedText = new StringBuilder();

    SpringAiStreamEventAdapter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    List<StreamEvent> toStreamEvents(AssistantMessage assistantMessage) {
        List<StreamEvent> events = new ArrayList<>();
        String delta = toTextDelta(assistantMessage.getText());
        if (!delta.isEmpty()) {
            events.add(StreamEvent.textDelta(delta));
        }
        // Spring AI 已经聚合出完整 tool call，当前 Step 2 只向上游暴露 TOOL_USE_END。
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            events.add(StreamEvent.toolUseEnd(toToolCallNode(toolCall)));
        }
        if (events.isEmpty() && isEmptyMessage(assistantMessage)) {
            events.add(StreamEvent.complete());
        }
        return events;
    }

    private String toTextDelta(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String emitted = emittedText.toString();
        if (text.startsWith(emitted)) {
            // Spring AI Anthropic 当前会暴露累计文本；无显式协议标记时只能用此前缀关系去重。
            String delta = text.substring(emitted.length());
            emittedText.setLength(0);
            emittedText.append(text);
            return delta;
        }
        // 兼容 SDK 直接给真实 delta 的情况；只有累计全文才做去重。
        emittedText.append(text);
        return text;
    }

    private boolean isEmptyMessage(AssistantMessage assistantMessage) {
        return (assistantMessage.getText() == null || assistantMessage.getText().isEmpty())
                && assistantMessage.getToolCalls().isEmpty();
    }

    private JsonNode toToolCallNode(AssistantMessage.ToolCall toolCall) {
        var node = objectMapper.createObjectNode();
        node.put("id", toolCall.id());
        node.put("name", toolCall.name());
        node.set("input", parseJson(toolCall.arguments()));
        return node;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid tool call JSON: " + json, ex);
        }
    }
}
