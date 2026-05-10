package ai.cerbur.cimo.client;

import java.net.URI;
import java.net.URISyntaxException;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import ai.cerbur.cimo.client.anthropic.SpringAiAnthropicClient;
import ai.cerbur.cimo.client.openai.OpenAiClient;
import ai.cerbur.cimo.config.AnthropicProperties;
import ai.cerbur.cimo.config.CimoProperties;

/**
 * 根据 Cimo 全局 provider 配置创建唯一需要的 LLM client，避免未选中的 provider 过早初始化。
 */
@Component
public class ClientFactory {

    @Autowired
    private CimoProperties cimoProperties;

    @Autowired
    private AnthropicProperties anthropicProperties;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 按当前配置选择并创建 provider client；未选中的 provider 不参与校验和 SDK client 创建。
     */
    public Client createClient() {
        if ("anthropic".equals(cimoProperties.provider())) {
            return new SpringAiAnthropicClient(
                    createAnthropicChatModel(),
                    objectMapper,
                    anthropicProperties.maxTokens());
        }
        if ("openai".equals(cimoProperties.provider())) {
            return new OpenAiClient();
        }
        throw new IllegalArgumentException("Unsupported provider: " + cimoProperties.provider());
    }

    private AnthropicChatModel createAnthropicChatModel() {
        validateAnthropicProperties();
        printAnthropicDebugConfigIfEnabled();
        // 只有 provider=anthropic 时才构建 SDK model，避免未选中 provider 的配置缺失阻塞启动。
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
        if (!cimoProperties.debug()) {
            return;
        }
        System.out.println("Anthropic config: "
                + "model=" + anthropicProperties.model()
                + ", baseUrl=" + anthropicProperties.baseUrl()
                + ", maxTokens=" + anthropicProperties.maxTokens()
                + ", debug=" + cimoProperties.debug()
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

}
