package ai.cerbur.cimo.agent;

import ai.cerbur.cimo.entry.event.AgentEventHandler;

/**
 * Agent 核心编排接口，负责把用户输入转成 LLM 请求、工具执行和入口事件。
 */
public interface AgentLoop {
    void start(AgentContext context, AgentEventHandler handler);

    void processInput(String userInput);

    void shutdown();
}
