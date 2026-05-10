package ai.cerbur.cimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI provider 的预留配置，只做字段绑定，不在 Step 1 触发 client 创建或校验。
 */
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
