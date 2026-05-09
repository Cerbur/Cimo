package ai.cerbur.cimo.agent.history;

import java.util.ArrayList;
import java.util.List;

import ai.cerbur.cimo.client.model.ChatMessage;

public class MessageHistory {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void add(ChatMessage message) {
        messages.add(message);
    }

    public List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }
}
