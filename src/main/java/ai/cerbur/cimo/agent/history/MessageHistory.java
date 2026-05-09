package ai.cerbur.cimo.agent.history;

import java.util.ArrayList;
import java.util.List;

import ai.cerbur.cimo.client.model.ChatMessage;

public class MessageHistory {

    private final List<ChatMessage> messages = new ArrayList<>();
    private final int maxMessages;

    public MessageHistory(int maxMessages) {
        this.maxMessages = maxMessages;
    }

    public void add(ChatMessage message) {
        messages.add(message);
        trim();
    }

    public List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }

    private void trim() {
        while (messages.size() > maxMessages) {
            messages.removeFirst();
        }
    }
}
