package ai.cerbur.cimo.client.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.cerbur.cimo.client.model.StreamEvent;
import ai.cerbur.cimo.client.model.StreamEventType;

class SpringAiStreamEventAdapterTests {

    private final SpringAiStreamEventAdapter adapter = new SpringAiStreamEventAdapter(new ObjectMapper());

    @Test
    void cumulativeTextChunksAreConvertedToDeltas() {
        List<StreamEvent> first = adapter.toStreamEvents(new AssistantMessage("hello"));
        List<StreamEvent> second = adapter.toStreamEvents(new AssistantMessage("hello world"));
        List<StreamEvent> duplicate = adapter.toStreamEvents(new AssistantMessage("hello world"));

        assertThat(first).extracting(StreamEvent::content).containsExactly("hello");
        assertThat(second).extracting(StreamEvent::content).containsExactly(" world");
        assertThat(duplicate).isEmpty();
    }

    @Test
    void realDeltaChunksPassThrough() {
        List<StreamEvent> first = adapter.toStreamEvents(new AssistantMessage("hello"));
        List<StreamEvent> second = adapter.toStreamEvents(new AssistantMessage(" world"));

        assertThat(first).extracting(StreamEvent::content).containsExactly("hello");
        assertThat(second).extracting(StreamEvent::content).containsExactly(" world");
    }

    @Test
    void whitespaceDeltaIsPreserved() {
        List<StreamEvent> events = adapter.toStreamEvents(new AssistantMessage(" "));

        assertThat(events).extracting(StreamEvent::content).containsExactly(" ");
    }

    @Test
    void emptyAssistantMessageBecomesCompleteEvent() {
        List<StreamEvent> events = adapter.toStreamEvents(new AssistantMessage(""));

        assertThat(events).extracting(StreamEvent::type).containsExactly(StreamEventType.COMPLETE);
    }

    @Test
    void toolCallStillProducesToolUseEndEvent() {
        AssistantMessage message = AssistantMessage.builder()
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "read", "{\"path\":\"README.md\"}")))
                .build();

        List<StreamEvent> events = adapter.toStreamEvents(message);

        assertThat(events).extracting(StreamEvent::type).containsExactly(StreamEventType.TOOL_USE_END);
        assertThat(events.getFirst().toolCall().path("input").path("path").asText()).isEqualTo("README.md");
    }
}
