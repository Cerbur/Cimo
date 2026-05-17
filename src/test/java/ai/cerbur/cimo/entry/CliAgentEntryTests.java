package ai.cerbur.cimo.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.cerbur.cimo.agent.AgentContext;
import ai.cerbur.cimo.agent.AgentLoop;
import ai.cerbur.cimo.config.CimoProperties;
import ai.cerbur.cimo.entry.command.CliCommandHandler;
import ai.cerbur.cimo.entry.command.ExitCliCommandHandler;
import ai.cerbur.cimo.entry.event.AgentEvent;
import ai.cerbur.cimo.entry.event.AgentEventHandler;
import ai.cerbur.cimo.tool.registry.ToolRegistry;

@ExtendWith(OutputCaptureExtension.class)
class CliAgentEntryTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void defaultToolCallOutputDoesNotExposeRawJson(CapturedOutput output) {
        CliAgentEntry entry = entry(false);

        entry.onEvent(new AgentEvent.ToolCall("bash", bashEchoArguments("hello")));

        assertThat(output)
                .contains("Tool: bash echo hello")
                .doesNotContain("{\"command\":\"echo\",\"args\":[\"hello\"]}");
    }

    @Test
    void debugToolCallOutputIncludesRawJson(CapturedOutput output) {
        CliAgentEntry entry = entry(true);

        entry.onEvent(new AgentEvent.ToolCall("bash", bashEchoArguments("hello")));

        assertThat(output)
                .contains("Tool: bash echo hello")
                .contains("raw: {\"command\":\"echo\",\"args\":[\"hello\"]}");
    }

    @Test
    void toolResultOutputIncludesToolName(CapturedOutput output) {
        CliAgentEntry entry = entry(false);

        entry.onEvent(new AgentEvent.ToolResult("bash", "hello"));

        assertThat(output).contains("Result: bash: hello");
    }

    @Test
    void responseDeltaFlushesImmediately() {
        PrintStream originalOut = System.out;
        FlushCountingOutput output = new FlushCountingOutput();
        System.setOut(output);
        try {
            CliAgentEntry entry = entry(false);

            entry.onEvent(new AgentEvent.Response("hello"));

            assertThat(output.text()).isEqualTo("hello");
            assertThat(output.flushCount()).isGreaterThanOrEqualTo(1);
        }
        finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void toolOutputStartsOnNewLineAfterOpenResponse() {
        PrintStream originalOut = System.out;
        FlushCountingOutput output = new FlushCountingOutput();
        System.setOut(output);
        try {
            CliAgentEntry entry = entry(false);

            entry.onEvent(new AgentEvent.Response("hello"));
            entry.onEvent(new AgentEvent.ToolCall("bash", bashEchoArguments("world")));
            entry.onEvent(new AgentEvent.ToolResult("bash", "done"));

            assertThat(output.text()).contains("hello" + System.lineSeparator() + "Tool: bash echo world"
                    + System.lineSeparator() + "Result: bash: done" + System.lineSeparator());
        }
        finally {
            System.setOut(originalOut);
        }
    }

    @Test
    void exitCommandDoesNotEnterAgentLoop() {
        RecordingAgentLoop agentLoop = new RecordingAgentLoop();
        CliAgentEntry entry = entry(false, agentLoop, new ExitCliCommandHandler());

        boolean keepRunning = entry.handleUserInput("exit");

        assertThat(keepRunning).isFalse();
        assertThat(agentLoop.inputs()).isEmpty();
    }

    @Test
    void naturalLanguageInputEntersAgentLoop() {
        RecordingAgentLoop agentLoop = new RecordingAgentLoop();
        CliAgentEntry entry = entry(false, agentLoop, new ExitCliCommandHandler());

        boolean keepRunning = entry.handleUserInput("please explain quit command");

        assertThat(keepRunning).isTrue();
        assertThat(agentLoop.inputs()).containsExactly("please explain quit command");
    }

    private CliAgentEntry entry(boolean debug) {
        return entry(debug, new NoopAgentLoop(), new ExitCliCommandHandler());
    }

    private CliAgentEntry entry(boolean debug, AgentLoop agentLoop, CliCommandHandler cliCommandHandler) {
        CliAgentEntry entry = new CliAgentEntry();
        ReflectionTestUtils.setField(entry, "agentLoop", agentLoop);
        ReflectionTestUtils.setField(entry, "cimoProperties", new CimoProperties(
                "anthropic",
                debug,
                System.getProperty("user.dir"),
                5));
        ReflectionTestUtils.setField(entry, "toolRegistry", new ToolRegistry(List.of()));
        ReflectionTestUtils.setField(entry, "cliCommandHandler", cliCommandHandler);
        return entry;
    }

    private JsonNode bashEchoArguments(String text) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("command", "echo");
        arguments.putArray("args").add(text);
        return arguments;
    }

    private static class FlushCountingOutput extends PrintStream {

        private final ByteArrayOutputStream bytes;
        private int flushCount;

        FlushCountingOutput() {
            this(new ByteArrayOutputStream());
        }

        private FlushCountingOutput(ByteArrayOutputStream bytes) {
            super(bytes, true, StandardCharsets.UTF_8);
            this.bytes = bytes;
        }

        @Override
        public void flush() {
            super.flush();
            flushCount++;
        }

        private String text() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        private int flushCount() {
            return flushCount;
        }
    }

    private static class NoopAgentLoop implements AgentLoop {

        @Override
        public void start(AgentContext context, AgentEventHandler handler) {
        }

        @Override
        public void processInput(String userInput) {
        }

        @Override
        public void shutdown() {
        }
    }

    private static class RecordingAgentLoop extends NoopAgentLoop {

        private final List<String> inputs = new java.util.ArrayList<>();

        @Override
        public void processInput(String userInput) {
            inputs.add(userInput);
        }

        private List<String> inputs() {
            return inputs;
        }
    }
}
