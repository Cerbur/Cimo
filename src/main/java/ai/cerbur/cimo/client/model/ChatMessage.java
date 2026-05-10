package ai.cerbur.cimo.client.model;

import java.util.List;

/**
 * Cimo 内部统一消息模型，一条消息可以由文本、工具调用或工具结果等多个内容块组成。
 */
public record ChatMessage(
        ChatRole role,
        List<ContentBlock> content) {
}
