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
import ai.cerbur.cimo.config.SpringEnvironmentReader;

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
        printAnthropicDebugConfigIfEnabled();
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

    /**
     * 在全局调试开关打开时打印已绑定的 Anthropic 配置，帮助确认 CLI 启动时实际读取到的值。
     */
    private void printAnthropicDebugConfigIfEnabled() {
        boolean debug = SpringEnvironmentReader.getBoolean("cimo.debug", false);
        if (!debug) {
            return;
        }
        System.out.println("Anthropic config: "
                + "model=" + anthropicProperties.model()
                + ", baseUrl=" + anthropicProperties.baseUrl()
                + ", maxTokens=" + anthropicProperties.maxTokens()
                + ", debug=" + debug
                + ", apiKey=" + maskApiKey(anthropicProperties.apiKey()));
    }

    /**
     * API key 只暴露首尾少量字符，避免 debug 输出泄露完整凭证。
     */
    private static String maskApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return "";
        }
        String trimmed = apiKey.trim();
        if (trimmed.length() <= 8) {
            return "*".repeat(trimmed.length());
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4);
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
