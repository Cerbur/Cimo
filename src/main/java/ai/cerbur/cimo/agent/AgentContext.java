package ai.cerbur.cimo.agent;

import ai.cerbur.cimo.tool.registry.ToolRegistry;

/**
 * 单次 Agent 运行上下文，集中保存入口层传入的工作目录、提示词、工具和循环上限。
 */
public record AgentContext(
        String workingDirectory,
        String systemPrompt,
        ToolRegistry toolRegistry,
        int maxToolRounds) {
}
