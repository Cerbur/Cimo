package ai.cerbur.cimo.client.model;

public enum StreamEventType {
    TEXT_START,
    TEXT_DELTA,
    TEXT_END,
    TOOL_USE_START,
    TOOL_USE_DELTA,
    TOOL_USE_END,
    COMPLETE,
    ERROR
}
