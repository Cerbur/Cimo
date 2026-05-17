package ai.cerbur.cimo.entry.command;

import org.springframework.stereotype.Component;

/**
 * Step 2 的最小 CLI 指令处理器，只保留 Step 1 已存在的无斜杠退出语义。
 */
@Component
public class ExitCliCommandHandler implements CliCommandHandler {

    @Override
    public CliCommandResult handle(String input) {
        String command = input == null ? "" : input.trim();
        if ("exit".equalsIgnoreCase(command) || "quit".equalsIgnoreCase(command)) {
            return new CliCommandResult.ExitRequested();
        }
        return new CliCommandResult.NotCommand();
    }
}
