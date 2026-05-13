package ai.cerbur.cimo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.ClientFactory;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.StreamEvent;
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
}
