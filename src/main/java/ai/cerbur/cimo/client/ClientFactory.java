package ai.cerbur.cimo.client;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.cerbur.cimo.client.anthropic.SpringAiAnthropicClient;
import ai.cerbur.cimo.client.openai.OpenAiClient;
import ai.cerbur.cimo.config.AnthropicProperties;

@Component
public class ClientFactory {

    private final String provider;
    private final AnthropicProperties anthropicProperties;
    private final ObjectMapper objectMapper;

    public ClientFactory(
            @Value("${cimo.provider:anthropic}") String provider,
            AnthropicProperties anthropicProperties,
            ObjectMapper objectMapper) {
        this.provider = normalizeProvider(provider);
        this.anthropicProperties = anthropicProperties;
        this.objectMapper = objectMapper;
    }

    public Client createClient() {
        if ("anthropic".equals(provider)) {
            return new SpringAiAnthropicClient(createAnthropicChatModel(), objectMapper);
        }
        if ("openai".equals(provider)) {
            return new OpenAiClient();
        }
        throw new IllegalArgumentException("Unsupported provider: " + provider);
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

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "anthropic";
        }
        return provider.trim().toLowerCase();
    }
}
