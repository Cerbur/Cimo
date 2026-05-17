package ai.cerbur.cimo.entry.command;

/**
 * CLI 本地指令处理器，用于拦截不应进入 AgentLoop 的用户输入。
 */
public interface CliCommandHandler {

    /**
     * 识别并处理一条 CLI 输入；未识别时必须返回 NotCommand，让自然语言继续进入 AgentLoop。
     */
    CliCommandResult handle(String input);
}
