package ai.cerbur.cimo.entry.event;

/**
 * Agent 生命周期状态枚举；Step 1 暂无真实状态消费者，保留为后续扩展边界。
 */
public enum AgentState {
    INITIALIZING,
    RUNNING,
    WAITING_FOR_TOOL,
    COMPLETED,
    ERROR,
    SHUTDOWN
}
