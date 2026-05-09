package ai.cerbur.cimo.entry.event;

public enum AgentState {
    INITIALIZING,
    RUNNING,
    WAITING_FOR_TOOL,
    COMPLETED,
    ERROR,
    SHUTDOWN
}
