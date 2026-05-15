package ai.cerbur.cimo.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.cerbur.cimo.tool.ToolExecutionContext;
import ai.cerbur.cimo.tool.ToolResult;

class BashToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void exposesSingleCommandSchema() {
        BashTool tool = tool("n\n", new ByteArrayOutputStream(), 30, 1024);

        JsonNode schema = tool.getParameterSchema();

        assertThat(tool.getName()).isEqualTo("bash");
        assertThat(tool.getDescription()).contains("bash command");
        assertThat(schema.path("properties").path("command").path("type").asText()).isEqualTo("string");
        assertThat(schema.path("properties").has("args")).isFalse();
        assertThat(schema.path("properties").path("command").has("enum")).isFalse();
        assertThat(schema.path("required")).extracting(JsonNode::asText).containsExactly("command");
        assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
    }

    @Test
    void cancelsWhenConfirmationIsNotExactY() {
        ByteArrayOutputStream prompt = new ByteArrayOutputStream();
        BashTool tool = tool("yes\n", prompt, 30, 1024);

        ToolResult result = tool.execute(context(), arguments("echo should-not-run"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).contains("cancelled=true");
        assertThat(result.error()).contains("command: echo should-not-run");
        assertThat(result.exitCode()).isNull();
        assertThat(prompt.toString(StandardCharsets.UTF_8)).contains("请求执行 Bash：echo should-not-run");
    }

    @Test
    void cancelsWhenConfirmationIsEmptyOrEofOrUnreadable() {
        assertCancelled(tool("\n", new ByteArrayOutputStream(), 30, 1024));
        assertCancelled(tool("", new ByteArrayOutputStream(), 30, 1024));
        assertCancelled(tool(new BrokenInputStream(), new ByteArrayOutputStream(), 30, 1024));
    }

    @Test
    void executesCommandAfterConfirmation() {
        BashTool tool = tool("y\n", new ByteArrayOutputStream(), 30, 1024);

        ToolResult result = tool.execute(context(), arguments("printf 'hello'"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.error()).isEmpty();
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void preservesShellCompoundCommandSemantics() {
        BashTool tool = tool("y\n", new ByteArrayOutputStream(), 30, 1024);

        ToolResult result = tool.execute(context(), arguments("printf 'a\\nb\\n' | wc -l && printf done > result.txt"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).contains("2");
        assertThat(workspace.resolve("result.txt")).hasContent("done");
    }

    @Test
    void returnsNonZeroExitCodeAndStderr() {
        BashTool tool = tool("y\n", new ByteArrayOutputStream(), 30, 1024);

        ToolResult result = tool.execute(context(), arguments("printf 'bad' >&2; exit 7"));

        assertThat(result.success()).isFalse();
        assertThat(result.output()).isEmpty();
        assertThat(result.error()).isEqualTo("bad");
        assertThat(result.exitCode()).isEqualTo(7);
    }

    @Test
    void timesOutLongRunningCommand() {
        BashTool tool = tool("y\n", new ByteArrayOutputStream(), 1, 1024);

        ToolResult result = tool.execute(context(), arguments("sleep 5"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Command timed out after 1 seconds.");
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void timeoutReturnsEvenWhenChildProcessKeepsPipeOpen() {
        BashTool tool = new BashTool(1,
                new ByteArrayInputStream("y\n".getBytes(StandardCharsets.UTF_8)),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                1024,
                100);

        ToolResult result = assertTimeoutPreemptively(Duration.ofSeconds(3),
                () -> tool.execute(context(), arguments("sleep 1000 & wait")));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("Command timed out after 1 seconds.");
        assertThat(result.exitCode()).isNull();
    }

    @Test
    void truncatesLargeOutputButStillDrainsProcess() {
        BashTool tool = tool("y\n", new ByteArrayOutputStream(), 30, 12);

        ToolResult result = tool.execute(context(), arguments("printf '1234567890abcdefghij'"));

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("1234567890ab\n[output truncated at 12 bytes]");
        assertThat(result.exitCode()).isEqualTo(0);
    }

    @Test
    void rejectsMissingCommand() {
        BashTool tool = tool("y\n", new ByteArrayOutputStream(), 30, 1024);

        ToolResult result = tool.execute(context(), objectMapper.createObjectNode());

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Command is required.");
    }

    private ToolExecutionContext context() {
        return new ToolExecutionContext(workspace);
    }

    private ObjectNode arguments(String command) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("command", command);
        return arguments;
    }

    private BashTool tool(String confirmation, ByteArrayOutputStream prompt, int timeoutSeconds, int outputLimitBytes) {
        return tool(new ByteArrayInputStream(confirmation.getBytes(StandardCharsets.UTF_8)),
                prompt,
                timeoutSeconds,
                outputLimitBytes);
    }

    private BashTool tool(InputStream confirmation, ByteArrayOutputStream prompt, int timeoutSeconds, int outputLimitBytes) {
        return new BashTool(timeoutSeconds,
                confirmation,
                new PrintStream(prompt, true, StandardCharsets.UTF_8),
                outputLimitBytes);
    }

    private void assertCancelled(BashTool tool) {
        ToolResult result = tool.execute(context(), arguments("echo should-not-run"));

        assertThat(result.success()).isFalse();
        assertThat(result.error()).contains("cancelled=true");
    }

    private static class BrokenInputStream extends InputStream {

        @Override
        public int read() throws IOException {
            throw new IOException("cannot read confirmation");
        }
    }
}
