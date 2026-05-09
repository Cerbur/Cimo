package ai.cerbur.cimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cimo.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        String baseUrl,
        int maxTokens) {

    public AnthropicProperties {
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "" : model;
        baseUrl = baseUrl == null ? "" : baseUrl;
        maxTokens = maxTokens <= 0 ? 4096 : maxTokens;
    }
}
