package ai.cerbur.cimo.entry.command;

/**
 * CLI 本地指令处理结果，显式区分已处理、未识别和请求退出三类状态。
 */
public sealed interface CliCommandResult permits CliCommandResult.Handled, CliCommandResult.NotCommand,
        CliCommandResult.ExitRequested {

    /**
     * 指令已处理，CLI 可以继续读取下一条输入。
     */
    record Handled() implements CliCommandResult {
    }

    /**
     * 当前输入不是本地指令，应交给 AgentLoop 作为自然语言处理。
     */
    record NotCommand() implements CliCommandResult {
    }

    /**
     * 当前输入请求退出 CLI。
     */
    record ExitRequested() implements CliCommandResult {
    }
}
