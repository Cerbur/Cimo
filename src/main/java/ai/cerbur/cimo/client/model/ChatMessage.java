package ai.cerbur.cimo.client.model;

import java.util.List;

public record ChatMessage(
        ChatRole role,
        List<ContentBlock> content) {
}
