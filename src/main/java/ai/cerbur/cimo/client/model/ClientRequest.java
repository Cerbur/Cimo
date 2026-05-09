package ai.cerbur.cimo.client.model;

import java.util.Collection;
import java.util.List;

import ai.cerbur.cimo.tool.Tool;

public record ClientRequest(
        String systemPrompt,
        List<ChatMessage> messages,
        Collection<Tool> tools) {
}
