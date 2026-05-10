package ai.cerbur.cimo.client.model;

import java.util.Collection;
import java.util.List;

import ai.cerbur.cimo.tool.Tool;

/**
 * 发给 LLM client 的完整请求快照，显式拆分 system prompt、消息历史和本轮可见工具。
 */
public record ClientRequest(
        String systemPrompt,
        List<ChatMessage> messages,
        Collection<Tool> tools) {
}
