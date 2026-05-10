package ai.cerbur.cimo.client;

import reactor.core.publisher.Flux;

import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.StreamEvent;

/**
 * LLM provider 抽象，向 AgentLoop 暴露统一的流式事件而不是 provider SDK 对象。
 */
public interface Client {
    Flux<StreamEvent> chatStream(ClientRequest request);
}
