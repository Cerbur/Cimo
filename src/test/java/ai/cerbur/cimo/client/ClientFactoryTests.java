package ai.cerbur.cimo.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.cerbur.cimo.client.openai.OpenAiClient;
import ai.cerbur.cimo.config.AnthropicProperties;

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
                "openai",
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

    private ClientFactory anthropicFactory(String apiKey, String model, String baseUrl) {
        return new ClientFactory(
                "anthropic",
                new AnthropicProperties(apiKey, model, baseUrl, 4096),
                objectMapper);
    }
}
