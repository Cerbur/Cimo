package ai.cerbur.cimo.client.openai;

import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.StreamEvent;
import reactor.core.publisher.Flux;

/**
 * OpenAI provider 的占位实现，保留 provider 选择边界但不在 Step 1 提前实现协议适配。
 */
public class OpenAiClient implements Client {

    @Override
    public Flux<StreamEvent> chatStream(ClientRequest request) {
        return Flux.error(new UnsupportedOperationException("OpenAI provider is reserved for a later step."));
    }
}
