package ai.cerbur.cimo.agent;

import ai.cerbur.cimo.tool.registry.ToolRegistry;

public record AgentContext(
        String workingDirectory,
        String systemPrompt,
        ToolRegistry toolRegistry,
        int maxToolRounds) {
}
