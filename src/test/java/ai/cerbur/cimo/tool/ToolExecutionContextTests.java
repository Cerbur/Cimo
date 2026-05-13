package ai.cerbur.cimo.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ToolExecutionContextTests {

    @Test
    void normalizesWorkingDirectoryToAbsolutePath() {
        ToolExecutionContext context = new ToolExecutionContext(Path.of("."));

        assertThat(context.workingDirectory())
                .isAbsolute()
                .isEqualTo(Path.of(".").toAbsolutePath().normalize());
    }
}
