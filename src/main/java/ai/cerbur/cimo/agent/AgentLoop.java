package ai.cerbur.cimo.agent;

import ai.cerbur.cimo.entry.event.AgentEventHandler;

public interface AgentLoop {
    void start(AgentContext context, AgentEventHandler handler);

    void processInput(String userInput);

    void shutdown();
}
