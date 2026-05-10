package ai.cerbur.cimo.entry.event;

/**
 * Agent 事件消费者接口，入口层通过它接收流式文本、工具事件和错误信息。
 */
public interface AgentEventHandler {
    void onEvent(AgentEvent event);
}
