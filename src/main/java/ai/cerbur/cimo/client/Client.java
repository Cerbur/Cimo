package ai.cerbur.cimo.client;

import reactor.core.publisher.Flux;

import ai.cerbur.cimo.client.model.ClientRequest;
import ai.cerbur.cimo.client.model.StreamEvent;

public interface Client {
    Flux<StreamEvent> chatStream(ClientRequest request);
}
