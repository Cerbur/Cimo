package ai.cerbur.cimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cimo.openai")
public record OpenAiProperties(
        String apiKey,
        String model,
        String baseUrl) {

    public OpenAiProperties {
        apiKey = apiKey == null ? "" : apiKey;
        model = model == null ? "" : model;
        baseUrl = baseUrl == null ? "" : baseUrl;
    }
}
