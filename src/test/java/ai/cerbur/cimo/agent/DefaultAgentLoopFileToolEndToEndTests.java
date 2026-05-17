package ai.cerbur.cimo.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.ClientFactory;
import ai.cerbur.cimo.client.model.ChatMessage;
import ai.cerbur.cimo.client.model.ChatRole;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.ContentBlock;
import ai.cerbur.cimo.client.model.StreamEvent;
import ai.cerbur.cimo.entry.event.AgentEvent;
import ai.cerbur.cimo.tool.file.FileToolSecurity;
import ai.cerbur.cimo.tool.impl.EditTool;
import ai.cerbur.cimo.tool.impl.ListTool;
import ai.cerbur.cimo.tool.impl.ReadTool;
import ai.cerbur.cimo.tool.impl.SearchTool;
import ai.cerbur.cimo.tool.impl.WriteTool;
import ai.cerbur.cimo.tool.registry.ToolRegistry;
import reactor.core.publisher.Flux;

/**
 * S2-T05 的最小端到端替身验收，使用 FakeClient 驱动真实 AgentLoop 和真实文件工具完成读、搜、改闭环。
 */
class DefaultAgentLoopFileToolEndToEndTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path workspace;

    @Test
    void drivesRealFileToolsThroughDeterministicAgentLoop() throws IOException {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src/Task.txt"),
                "name=Cimo\nstatus=TODO\nmarker=TARGET_TOKEN\n",
                StandardCharsets.UTF_8);

        FileToolSecurity security = new FileToolSecurity();
        ToolRegistry toolRegistry = new ToolRegistry(List.of(
                new ListTool(security),
                new SearchTool(security),
                new ReadTool(security),
                new EditTool(security),
                new WriteTool(security)));
        ScriptedFileToolClient client = new ScriptedFileToolClient();
        DefaultAgentLoop loop = new DefaultAgentLoop(new FakeClientFactory(client));
        List<AgentEvent> events = new ArrayList<>();

        loop.start(new AgentContext(
                workspace.toString(),
                "system",
                toolRegistry,
                10), events::add);
        loop.processInput("complete the task marker");

        assertThat(Files.readString(workspace.resolve("src/Task.txt"), StandardCharsets.UTF_8))
                .isEqualTo("name=Cimo\nstatus=DONE\nmarker=TARGET_TOKEN\n");
        assertThat(Files.readString(workspace.resolve("reports/summary.txt"), StandardCharsets.UTF_8))
                .isEqualTo("updated src/Task.txt to status=DONE\n");
        assertThat(client.observedToolResultIds)
                .containsExactly("list-1", "search-1", "read-1", "edit-1", "write-1", "verify-1");
        assertThat(events)
                .filteredOn(AgentEvent.ToolCall.class::isInstance)
                .extracting(event -> ((AgentEvent.ToolCall) event).toolName())
                .containsExactly("list", "search", "read", "edit", "write", "search");
        assertThat(events)
                .anySatisfy(event -> assertThat(event).isEqualTo(new AgentEvent.Response("file tools done")));
    }

    private JsonNode toolCall(String id, String name, JsonNode input) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("id", id);
        node.put("name", name);
        node.set("input", input);
        return node;
    }

    private ObjectNode arguments() {
        return objectMapper.createObjectNode();
    }

    /**
     * 固定工具调用序列的 fake client，每一轮都会检查上一轮真实工具结果是否被回传给模型上下文。
     */
    private final class ScriptedFileToolClient implements Client {

        private final List<String> observedToolResultIds = new ArrayList<>();
        private int calls;

        @Override
        public Flux<StreamEvent> chatStream(ClientRequest request) {
            calls++;
            observePreviousToolResult(request);
            return switch (calls) {
                case 1 -> Flux.just(StreamEvent.toolUseEnd(toolCall("list-1", "list",
                        arguments().put("path", "."))));
                case 2 -> Flux.just(StreamEvent.toolUseEnd(toolCall("search-1", "search",
                        arguments()
                                .put("query", "TARGET_TOKEN")
                                .put("path", "src"))));
                case 3 -> Flux.just(StreamEvent.toolUseEnd(toolCall("read-1", "read",
                        arguments().put("path", "src/Task.txt"))));
                case 4 -> Flux.just(StreamEvent.toolUseEnd(toolCall("edit-1", "edit",
                        arguments()
                                .put("path", "src/Task.txt")
                                .put("oldString", "status=TODO")
                                .put("newString", "status=DONE"))));
                case 5 -> Flux.just(StreamEvent.toolUseEnd(toolCall("write-1", "write",
                        arguments()
                                .put("path", "reports/summary.txt")
                                .put("content", "updated src/Task.txt to status=DONE\n")
                                .put("createParentDirectories", true))));
                case 6 -> Flux.just(StreamEvent.toolUseEnd(toolCall("verify-1", "search",
                        arguments()
                                .put("query", "status=DONE")
                                .put("path", "src/Task.txt"))));
                case 7 -> Flux.just(StreamEvent.textDelta("file tools done"));
                default -> Flux.error(new AssertionError("Unexpected client call: " + calls));
            };
        }

        /**
         * 每一轮 fake assistant 都读取上一轮真实工具结果，锁住 tool_result 回传给后续回合的闭环。
         */
        private void observePreviousToolResult(ClientRequest request) {
            if (calls == 1) {
                assertThat(request.messages())
                        .extracting(ChatMessage::role)
                        .containsExactly(ChatRole.USER);
                return;
            }

            ContentBlock.ToolResult result = lastToolResult(request);
            observedToolResultIds.add(result.toolUseId());
            switch (result.toolUseId()) {
                case "list-1" -> assertThat(result.content()).contains("[dir] src/");
                case "search-1" -> assertThat(result.content()).contains("content: Task.txt:3: marker=TARGET_TOKEN");
                case "read-1" -> assertThat(result.content()).contains("status=TODO");
                case "edit-1" -> assertThat(result.content()).contains("replacements: 1");
                case "write-1" -> assertThat(result.content()).contains("createdParentDirectories: 1");
                case "verify-1" -> assertThat(result.content()).contains("content: Task.txt:2: status=DONE");
                default -> throw new AssertionError("Unexpected tool result id: " + result.toolUseId());
            }
            assertThat(result.isError()).isFalse();
        }

        private ContentBlock.ToolResult lastToolResult(ClientRequest request) {
            ChatMessage lastMessage = request.messages().get(request.messages().size() - 1);
            assertThat(lastMessage.role()).isEqualTo(ChatRole.USER);
            assertThat(lastMessage.content()).hasSize(1);
            assertThat(lastMessage.content().getFirst()).isInstanceOf(ContentBlock.ToolResult.class);
            return (ContentBlock.ToolResult) lastMessage.content().getFirst();
        }
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
}
