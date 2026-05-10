package ai.cerbur.cimo.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.cerbur.cimo.client.openai.OpenAiClient;
import ai.cerbur.cimo.config.AnthropicProperties;
import ai.cerbur.cimo.config.CimoProperties;

@ExtendWith(OutputCaptureExtension.class)
class ClientFactoryTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void anthropicProviderRequiresApiKey() {
        ClientFactory factory = anthropicFactory("", "claude-sonnet-4-20250514", "https://api.anthropic.com");

        assertThatThrownBy(factory::createClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cimo.anthropic.api-key");
    }

    @Test
    void anthropicProviderRequiresModel() {
        ClientFactory factory = anthropicFactory("test-key", "", "https://api.anthropic.com");

        assertThatThrownBy(factory::createClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cimo.anthropic.model");
    }

    @Test
    void anthropicProviderRequiresBaseUrl() {
        ClientFactory factory = anthropicFactory("test-key", "claude-sonnet-4-20250514", "");

        assertThatThrownBy(factory::createClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cimo.anthropic.base-url");
    }

    @Test
    void anthropicProviderRequiresHttpBaseUrl() {
        ClientFactory factory = anthropicFactory("test-key", "claude-sonnet-4-20250514", "ftp://api.anthropic.com");

        assertThatThrownBy(factory::createClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cimo.anthropic.base-url");
    }

    @Test
    void anthropicProviderRequiresBaseUrlHost() {
        ClientFactory factory = anthropicFactory("test-key", "claude-sonnet-4-20250514", "https:///missing-host");

        assertThatThrownBy(factory::createClient)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cimo.anthropic.base-url");
    }

    @Test
    void openAiProviderDoesNotValidateAnthropicSettings() {
        Client client = new ClientFactory(
                cimoProperties("openai", false),
                new AnthropicProperties("", "", "", 0),
                objectMapper)
                .createClient();

        assertThat(client).isInstanceOf(OpenAiClient.class);
    }

    @Test
    void validAnthropicSettingsCreateClient() {
        ClientFactory factory = anthropicFactory(
                "test-key",
                "claude-sonnet-4-20250514",
                "https://api.anthropic.com");

        assertThatCode(factory::createClient).doesNotThrowAnyException();
    }

    @Test
    void anthropicDebugDisabledDoesNotPrintConfig(CapturedOutput output) {
        ClientFactory factory = anthropicFactory(
                "test-key-secret",
                "claude-sonnet-4-20250514",
                "https://api.anthropic.com",
                false);

        factory.createClient();

        assertThat(output).doesNotContain("Anthropic config:");
    }

    @Test
    void anthropicDebugEnabledPrintsSafeConfig(CapturedOutput output) {
        ClientFactory factory = anthropicFactory(
                "test-key-secret",
                "claude-sonnet-4-20250514",
                "https://api.anthropic.com",
                true);

        factory.createClient();

        assertThat(output)
                .contains("Anthropic config:")
                .contains("model=claude-sonnet-4-20250514")
                .contains("baseUrl=https://api.anthropic.com")
                .contains("maxTokens=4096")
                .contains("debug=true")
                .contains("apiKey=test...cret")
                .doesNotContain("test-key-secret");
    }

    private ClientFactory anthropicFactory(String apiKey, String model, String baseUrl) {
        return anthropicFactory(apiKey, model, baseUrl, false);
    }

    private ClientFactory anthropicFactory(String apiKey, String model, String baseUrl, boolean debug) {
        return new ClientFactory(
                cimoProperties("anthropic", debug),
                new AnthropicProperties(apiKey, model, baseUrl, 4096),
                objectMapper);
    }

    private CimoProperties cimoProperties(String provider, boolean debug) {
        return new CimoProperties(
                provider,
                debug,
                System.getProperty("user.dir"),
                5);
    }
}
