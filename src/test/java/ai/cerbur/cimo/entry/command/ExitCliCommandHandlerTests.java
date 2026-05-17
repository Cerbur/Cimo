package ai.cerbur.cimo.entry.command;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExitCliCommandHandlerTests {

    private final ExitCliCommandHandler handler = new ExitCliCommandHandler();

    @Test
    void recognizesExitAndQuitWithoutSlash() {
        assertThat(handler.handle("exit")).isInstanceOf(CliCommandResult.ExitRequested.class);
        assertThat(handler.handle(" quit ")).isInstanceOf(CliCommandResult.ExitRequested.class);
        assertThat(handler.handle("EXIT")).isInstanceOf(CliCommandResult.ExitRequested.class);
    }

    @Test
    void leavesNaturalLanguageInputForAgentLoop() {
        assertThat(handler.handle("please explain exit codes")).isInstanceOf(CliCommandResult.NotCommand.class);
        assertThat(handler.handle("/exit")).isInstanceOf(CliCommandResult.NotCommand.class);
    }
}
