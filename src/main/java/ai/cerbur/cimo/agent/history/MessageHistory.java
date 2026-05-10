package ai.cerbur.cimo.agent.history;

import java.util.ArrayList;
import java.util.List;

import ai.cerbur.cimo.client.model.ChatMessage;

/**
 * Step 1 的内存消息历史，只做追加和快照；上下文裁剪/压缩留到后续独立设计。
 */
public class MessageHistory {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void add(ChatMessage message) {
        messages.add(message);
    }

    public List<ChatMessage> snapshot() {
        return List.copyOf(messages);
    }
}
