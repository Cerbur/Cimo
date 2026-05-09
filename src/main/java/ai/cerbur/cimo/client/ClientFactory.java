package ai.cerbur.cimo.client;

import java.net.URI;
import java.net.URISyntaxException;

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
            return new SpringAiAnthropicClient(
                    createAnthropicChatModel(),
                    objectMapper,
                    anthropicProperties.maxTokens());
        }
        if ("openai".equals(provider)) {
            return new OpenAiClient();
        }
        throw new IllegalArgumentException("Unsupported provider: " + provider);
    }

    private AnthropicChatModel createAnthropicChatModel() {
        validateAnthropicProperties();
        AnthropicChatOptions.Builder options = AnthropicChatOptions.builder()
                .apiKey(anthropicProperties.apiKey())
                .model(anthropicProperties.model())
                .baseUrl(anthropicProperties.baseUrl());
        return AnthropicChatModel.builder()
                .options(options.build())
                .build();
    }

    private void validateAnthropicProperties() {
        requireNonBlank(anthropicProperties.apiKey(), "cimo.anthropic.api-key");
        requireNonBlank(anthropicProperties.model(), "cimo.anthropic.model");
        requireNonBlank(anthropicProperties.baseUrl(), "cimo.anthropic.base-url");
        requireHttpUrl(anthropicProperties.baseUrl(), "cimo.anthropic.base-url");
    }

    private static void requireNonBlank(String value, String propertyName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    propertyName + " must be configured when cimo.provider=anthropic.");
        }
    }

    private static void requireHttpUrl(String value, String propertyName) {
        URI uri;
        try {
            uri = new URI(value);
        }
        catch (URISyntaxException ex) {
            throw new IllegalStateException(propertyName + " must be a valid HTTP or HTTPS URL.", ex);
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalStateException(propertyName + " must be a valid HTTP or HTTPS URL.");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalStateException(propertyName + " must include a host.");
        }
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "anthropic";
        }
        return provider.trim().toLowerCase();
    }
}
