package ai.cerbur.cimo.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.stereotype.Component;

import ai.cerbur.cimo.client.anthropic.SpringAiAnthropicClient;
import ai.cerbur.cimo.client.openai.OpenAiClient;
import ai.cerbur.cimo.config.AnthropicProperties;
import ai.cerbur.cimo.config.CimoProperties;

@Component
public class ClientFactory {

    private final CimoProperties properties;
    private final AnthropicProperties anthropicProperties;
    private final ObjectMapper objectMapper;

    public ClientFactory(
            CimoProperties properties,
            AnthropicProperties anthropicProperties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.anthropicProperties = anthropicProperties;
        this.objectMapper = objectMapper;
    }

    public Client createClient() {
        String provider = properties.provider().trim().toLowerCase();
        if ("anthropic".equals(provider)) {
            return new SpringAiAnthropicClient(createAnthropicChatModel(), objectMapper);
        }
        if ("openai".equals(provider)) {
            return new OpenAiClient();
        }
        throw new IllegalArgumentException("Unsupported provider: " + properties.provider());
    }

    private AnthropicChatModel createAnthropicChatModel() {
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .model(anthropicProperties.model());
        if (!anthropicProperties.apiKey().isBlank()) {
            options.apiKey(anthropicProperties.apiKey());
        }
        if (!anthropicProperties.baseUrl().isBlank()) {
            options.baseUrl(anthropicProperties.baseUrl());
        }
        return AnthropicChatModel.builder()
                .options(options.build())
                .build();
    }
}
