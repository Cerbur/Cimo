package ai.cerbur.cimo.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.ClientFactory;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.StreamEvent;
import ai.cerbur.cimo.entry.event.AgentEvent;
import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolExecutionContext;
import ai.cerbur.cimo.tool.ToolResult;
import ai.cerbur.cimo.tool.registry.ToolRegistry;
import reactor.core.publisher.Flux;

class DefaultAgentLoopToolExecutionContextTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void passesNarrowExecutionContextToTool() {
        RecordingTool tool = new RecordingTool();
        DefaultAgentLoop loop = new DefaultAgentLoop(new FakeClientFactory(new ToolCallingClient()));
        Path workDir = Path.of("build", "..").toAbsolutePath().normalize();

        loop.start(new AgentContext(
                workDir.toString(),
                "system",
                new ToolRegistry(List.of(tool)),
                2), event -> {
                });

        loop.processInput("use tool");

        assertThat(tool.context).isNotNull();
        assertThat(tool.context.workingDirectory()).isEqualTo(workDir);
    }

    @Test
    void emitsErrorEventWhenClientStreamFails() {
        DefaultAgentLoop loop = new DefaultAgentLoop(new FakeClientFactory(new ErrorClient()));
        List<AgentEvent> events = new ArrayList<>();

        loop.start(new AgentContext(
                workspacePath(),
                "system",
                new ToolRegistry(List.of()),
                2), events::add);

        assertThatCode(() -> loop.processInput("hello")).doesNotThrowAnyException();
        assertThat(events)
                .anySatisfy(event -> assertThat(event)
                        .isEqualTo(new AgentEvent.Error("provider failed")));
    }

    @Test
    void emitsErrorEventWhenToolIsUnknown() {
        DefaultAgentLoop loop = new DefaultAgentLoop(new FakeClientFactory(new SingleToolCallClient("missing")));
        List<AgentEvent> events = new ArrayList<>();

        loop.start(new AgentContext(
                workspacePath(),
                "system",
                new ToolRegistry(List.of()),
                2), events::add);

        assertThatCode(() -> loop.processInput("use missing tool")).doesNotThrowAnyException();
        assertThat(events)
                .anySatisfy(event -> assertThat(event)
                        .isEqualTo(new AgentEvent.Error("Unknown tool: missing")));
    }

    @Test
    void emitsErrorEventWhenToolThrows() {
        DefaultAgentLoop loop = new DefaultAgentLoop(new FakeClientFactory(new SingleToolCallClient("broken")));
        List<AgentEvent> events = new ArrayList<>();

        loop.start(new AgentContext(
                workspacePath(),
                "system",
                new ToolRegistry(List.of(new ThrowingTool())),
                2), events::add);

        assertThatCode(() -> loop.processInput("use broken tool")).doesNotThrowAnyException();
        assertThat(events)
                .anySatisfy(event -> assertThat(event)
                        .isEqualTo(new AgentEvent.Error("tool exploded")));
    }

    private String workspacePath() {
        return Path.of("build", "..").toAbsolutePath().normalize().toString();
    }

    private JsonNode toolCall(String id, String name, JsonNode input) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("name", name);
        node.set("input", input);
        return node;
    }

    private static class FakeClientFactory extends ClientFactory {

        private final Client client;

        FakeClientFactory(Client client) {
            this.client = client;
        }

        @Override
        public Client createClient() {
            return client;
        }
    }

    private class ToolCallingClient implements Client {

        private int calls;

        @Override
        public Flux<StreamEvent> chatStream(ClientRequest request) {
            calls++;
            if (calls == 1) {
                return Flux.just(StreamEvent.toolUseEnd(toolCall(
                        "tool-1",
                        "record",
                        objectMapper.createObjectNode())));
            }
            return Flux.just(StreamEvent.textDelta("done"));
        }
    }

    private static class ErrorClient implements Client {

        @Override
        public Flux<StreamEvent> chatStream(ClientRequest request) {
            return Flux.just(StreamEvent.error("provider failed"));
        }
    }

    private class SingleToolCallClient implements Client {

        private final String toolName;

        SingleToolCallClient(String toolName) {
            this.toolName = toolName;
        }

        @Override
        public Flux<StreamEvent> chatStream(ClientRequest request) {
            return Flux.just(StreamEvent.toolUseEnd(toolCall(
                    "tool-1",
                    toolName,
                    objectMapper.createObjectNode())));
        }
    }

    private static class RecordingTool implements Tool {

        private ToolExecutionContext context;

        @Override
        public String getName() {
            return "record";
        }

        @Override
        public String getDescription() {
            return "Records the execution context.";
        }

        @Override
        public JsonNode getParameterSchema() {
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            this.context = context;
            return new ToolResult(true, "ok", "", null);
        }
    }

    private static class ThrowingTool implements Tool {

        @Override
        public String getName() {
            return "broken";
        }

        @Override
        public String getDescription() {
            return "Throws while executing.";
        }

        @Override
        public JsonNode getParameterSchema() {
            return new ObjectMapper().createObjectNode();
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            throw new IllegalStateException("tool exploded");
        }
    }
}
