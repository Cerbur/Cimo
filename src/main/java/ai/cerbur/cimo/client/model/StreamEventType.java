package ai.cerbur.cimo.client.model;

/**
 * 流式事件类型集合；当前 Step 1 只落地文本增量、工具调用结束、完成和错误事件。
 */
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
