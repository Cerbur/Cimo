package ai.cerbur.cimo.client.openai;

import ai.cerbur.cimo.client.Client;
import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.StreamEvent;
import reactor.core.publisher.Flux;

public class OpenAiClient implements Client {

    @Override
    public Flux<StreamEvent> chatStream(ClientRequest request) {
        return Flux.error(new UnsupportedOperationException("OpenAI provider is reserved for a later step."));
    }
}
