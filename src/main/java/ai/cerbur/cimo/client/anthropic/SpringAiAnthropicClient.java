package ai.cerbur.cimo.client.anthropic;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.model.ChatMessage;
import ai.cerbur.cimo.client.model.ChatRole;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.ContentBlock;
import ai.cerbur.cimo.client.model.StreamEvent;
import ai.cerbur.cimo.tool.Tool;
import reactor.core.publisher.Flux;

public class SpringAiAnthropicClient implements Client {

    private final AnthropicChatModel chatModel;
    private final ObjectMapper objectMapper;

    public SpringAiAnthropicClient(AnthropicChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    @Override
    public Flux<StreamEvent> chatStream(ClientRequest request) {
        Prompt prompt = new Prompt(toSpringAiMessages(request), toAnthropicOptions(request));
        return chatModel.stream(prompt)
                .flatMapIterable(response -> response.getResults().stream()
                        .flatMap(generation -> toStreamEvents(generation.getOutput()).stream())
                        .toList())
                .onErrorResume(ex -> Flux.just(StreamEvent.error(ex.getMessage())));
    }

    private List<Message> toSpringAiMessages(ClientRequest request) {
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(request.systemPrompt()));
        for (ChatMessage message : request.messages()) {
            messages.add(toSpringAiMessage(message));
        }
        return messages;
    }

    private Message toSpringAiMessage(ChatMessage message) {
        if (message.role() == ChatRole.ASSISTANT) {
            StringBuilder text = new StringBuilder();
            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
            for (ContentBlock block : message.content()) {
                switch (block) {
                    case ContentBlock.Text textBlock -> text.append(textBlock.text());
                    case ContentBlock.ToolUse toolUse -> toolCalls.add(new AssistantMessage.ToolCall(
                            toolUse.id(),
                            "function",
                            toolUse.name(),
                            toJsonString(toolUse.input())));
                    case ContentBlock.ToolResult ignored -> throw new IllegalArgumentException(
                            "tool_result blocks must be sent as user/tool response messages.");
                }
            }
            return AssistantMessage.builder()
                    .content(text.toString())
                    .toolCalls(toolCalls)
                    .build();
        }

        if (message.content().stream().allMatch(ContentBlock.ToolResult.class::isInstance)) {
            List<ToolResponseMessage.ToolResponse> responses = message.content()
                    .stream()
                    .map(ContentBlock.ToolResult.class::cast)
                    .map(result -> new ToolResponseMessage.ToolResponse(result.toolUseId(), "tool", result.content()))
                    .toList();
            return ToolResponseMessage.builder().responses(responses).build();
        }

        String text = message.content()
                .stream()
                .filter(ContentBlock.Text.class::isInstance)
                .map(ContentBlock.Text.class::cast)
                .map(ContentBlock.Text::text)
                .reduce("", String::concat);
        return new UserMessage(text);
    }

    private AnthropicChatOptions toAnthropicOptions(ClientRequest request) {
        return AnthropicChatOptions.builder()
                .maxTokens(1024)
                .internalToolExecutionEnabled(false)
                .disableParallelToolUse(true)
                .toolCallbacks(request.tools().stream().map(this::toToolCallback).toList())
                .build();
    }

    private ToolCallback toToolCallback(Tool tool) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(tool.getName())
                        .description(tool.getDescription())
                        .inputSchema(toJsonString(tool.getParameterSchema()))
                        .build();
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException("Cimo AgentLoop executes tools explicitly.");
            }
        };
    }

    private List<StreamEvent> toStreamEvents(AssistantMessage assistantMessage) {
        List<StreamEvent> events = new ArrayList<>();
        if (assistantMessage.getText() != null && !assistantMessage.getText().isBlank()) {
            events.add(StreamEvent.textDelta(assistantMessage.getText()));
        }
        for (AssistantMessage.ToolCall toolCall : assistantMessage.getToolCalls()) {
            events.add(StreamEvent.toolUseEnd(toToolCallNode(toolCall)));
        }
        if (events.isEmpty()) {
            events.add(StreamEvent.complete());
        }
        return events;
    }

    private JsonNode toToolCallNode(AssistantMessage.ToolCall toolCall) {
        var node = objectMapper.createObjectNode();
        node.put("id", toolCall.id());
        node.put("name", toolCall.name());
        node.set("input", parseJson(toolCall.arguments()));
        return node;
    }

    private JsonNode parseJson(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Invalid tool call JSON: " + json, ex);
        }
    }

    private String toJsonString(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        }
        catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Unable to serialize JSON node.", ex);
        }
    }
}
