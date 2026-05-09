package ai.cerbur.cimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cimo.anthropic")
public record AnthropicProperties(
        String apiKey,
        String model,
        String baseUrl) {

    public AnthropicProperties {
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null || model.isBlank() ? "claude-sonnet-4-20250514" : model;
        baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.anthropic.com" : baseUrl;
    }
}
