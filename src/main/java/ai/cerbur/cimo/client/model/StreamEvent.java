package ai.cerbur.cimo.client.model;

import com.fasterxml.jackson.databind.JsonNode;

public record StreamEvent(
        StreamEventType type,
        String content,
        JsonNode toolCall) {

    public static StreamEvent textDelta(String content) {
        return new StreamEvent(StreamEventType.TEXT_DELTA, content, null);
    }

    public static StreamEvent toolUseEnd(JsonNode toolCall) {
        return new StreamEvent(StreamEventType.TOOL_USE_END, null, toolCall);
    }

    public static StreamEvent complete() {
        return new StreamEvent(StreamEventType.COMPLETE, null, null);
    }

    public static StreamEvent error(String message) {
        return new StreamEvent(StreamEventType.ERROR, message, null);
    }
}
