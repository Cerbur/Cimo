package ai.cerbur.cimo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Component;

import ai.cerbur.cimo.agent.history.MessageHistory;
import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.ClientFactory;
import ai.cerbur.cimo.client.model.ChatMessage;
import ai.cerbur.cimo.client.model.ChatRole;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.ContentBlock;
import ai.cerbur.cimo.client.model.StreamEvent;
import ai.cerbur.cimo.client.model.StreamEventType;
import ai.cerbur.cimo.entry.event.AgentEvent;
import ai.cerbur.cimo.entry.event.AgentEventHandler;
import ai.cerbur.cimo.entry.event.AgentState;
import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolResult;

/**
 * 默认 Agent Loop 实现，串联模型流式响应、工具调用执行和消息历史追加。
 */
@Component
public class DefaultAgentLoop implements AgentLoop {

    private final Client client;
    private final MessageHistory history = new MessageHistory();
    private AgentContext context;
    private AgentEventHandler handler;

    public DefaultAgentLoop(ClientFactory clientFactory) {
        this.client = clientFactory.createClient();
    }

    @Override
    public void start(AgentContext context, AgentEventHandler handler) {
        this.context = Objects.requireNonNull(context, "context");
        this.handler = Objects.requireNonNull(handler, "handler");
        emit(new AgentEvent.StatusChange(AgentState.RUNNING));
    }

    @Override
    public void processInput(String userInput) {
        if (context == null) {
            throw new IllegalStateException("AgentLoop has not started.");
        }
        history.add(new ChatMessage(ChatRole.USER, List.of(new ContentBlock.Text(userInput))));
        emit(new AgentEvent.Thinking("Calling Anthropic..."));

        // 每一轮最多只处理一次 assistant turn 和随后的工具结果，避免模型持续要求工具时无限循环。
        for (int round = 0; round < context.maxToolRounds(); round++) {
            AssistantTurn turn = callClient();
            history.add(new ChatMessage(ChatRole.ASSISTANT, turn.content()));
            if (turn.toolUses().isEmpty()) {
                emit(new AgentEvent.Response("\n"));
                emit(new AgentEvent.StatusChange(AgentState.COMPLETED));
                return;
            }

            List<ContentBlock> toolResults = new ArrayList<>();
            for (ContentBlock.ToolUse toolUse : turn.toolUses()) {
                emit(new AgentEvent.StatusChange(AgentState.WAITING_FOR_TOOL));
                emit(new AgentEvent.ToolCall(toolUse.name(), toolUse.input()));
                Tool tool = context.toolRegistry().getTool(toolUse.name())
                        .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolUse.name()));
                ToolResult result = tool.execute(toolUse.input());
                String content = result.success() ? result.output() : result.error();
                emit(new AgentEvent.ToolResult(toolUse.name(), content));
                // toolUseId 必须沿用模型给出的 id，否则 provider 无法把结果关联回对应 tool_use。
                toolResults.add(new ContentBlock.ToolResult(toolUse.id(), content, !result.success()));
            }
            history.add(new ChatMessage(ChatRole.USER, toolResults));
        }

        emit(new AgentEvent.Error("Reached max tool rounds: " + context.maxToolRounds()));
        emit(new AgentEvent.StatusChange(AgentState.ERROR));
    }

    @Override
    public void shutdown() {
        emit(new AgentEvent.StatusChange(AgentState.SHUTDOWN));
    }

    private AssistantTurn callClient() {
        List<ContentBlock> content = new ArrayList<>();
        List<ContentBlock.ToolUse> toolUses = new ArrayList<>();
        StringBuilder currentText = new StringBuilder();

        ClientRequest request = new ClientRequest(
                context.systemPrompt(),
                history.snapshot(),
                context.toolRegistry().allTools());

        client.chatStream(request).toIterable().forEach(event -> {
            if (event.type() == StreamEventType.TEXT_DELTA && event.content() != null) {
                currentText.append(event.content());
                emit(new AgentEvent.Response(event.content()));
            }
            else if (event.type() == StreamEventType.TOOL_USE_END && event.toolCall() != null) {
                // 在记录 tool_use 前先落盘已有文本，保留 assistant turn 中 text 与 tool_use 的原始顺序。
                flushText(content, currentText);
                ContentBlock.ToolUse toolUse = new ContentBlock.ToolUse(
                        event.toolCall().path("id").asText(),
                        event.toolCall().path("name").asText(),
                        event.toolCall().path("input"));
                content.add(toolUse);
                toolUses.add(toolUse);
            }
            else if (event.type() == StreamEventType.ERROR) {
                throw new IllegalStateException(event.content());
            }
        });
        flushText(content, currentText);
        return new AssistantTurn(List.copyOf(content), List.copyOf(toolUses));
    }

    private void flushText(List<ContentBlock> content, StringBuilder currentText) {
        if (!currentText.isEmpty()) {
            content.add(new ContentBlock.Text(currentText.toString()));
            currentText.setLength(0);
        }
    }

    private void emit(AgentEvent event) {
        if (handler != null) {
            handler.onEvent(event);
        }
    }

    private record AssistantTurn(List<ContentBlock> content, List<ContentBlock.ToolUse> toolUses) {
    }
}
