package ai.cerbur.cimo.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import ai.cerbur.cimo.agent.AgentContext;
import ai.cerbur.cimo.agent.AgentLoop;
import ai.cerbur.cimo.config.CimoProperties;
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

    private CliAgentEntry entry(boolean debug) {
        return new CliAgentEntry(
                new NoopAgentLoop(),
                new CimoProperties(
                        "anthropic",
                        debug,
                        System.getProperty("user.dir"),
                        5),
                new ToolRegistry(List.of()));
    }

    private JsonNode bashEchoArguments(String text) {
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("command", "echo");
        arguments.putArray("args").add(text);
        return arguments;
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
}
