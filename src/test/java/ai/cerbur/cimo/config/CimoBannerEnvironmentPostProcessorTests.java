package ai.cerbur.cimo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class CimoBannerEnvironmentPostProcessorTests {

    private final CimoBannerEnvironmentPostProcessor postProcessor = new CimoBannerEnvironmentPostProcessor();

    @Test
    void exposesConfiguredAnthropicModelForResourceBanner() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cimo.provider", "anthropic")
                .withProperty("cimo.anthropic.model", "test-model")
                .withProperty("cimo.anthropic.api-key", "secret-key")
                .withProperty("cimo.anthropic.base-url", "https://example.invalid");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("cimo.banner.provider")).isEqualTo("anthropic");
        assertThat(environment.getProperty("cimo.banner.model")).isEqualTo("test-model");
        assertThat(environment.getProperty("cimo.banner.model"))
                .doesNotContain("secret-key")
                .doesNotContain("https://example.invalid");
    }

    @Test
    void exposesPlaceholderWhenSelectedProviderModelIsBlank() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cimo.provider", "openai")
                .withProperty("cimo.openai.model", " ");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("cimo.banner.provider")).isEqualTo("openai");
        assertThat(environment.getProperty("cimo.banner.model")).isEqualTo("<model not configured>");
    }

    @Test
    void defaultsToAnthropicProviderWhenProviderIsNotConfigured() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cimo.anthropic.model", "default-model");

        postProcessor.postProcessEnvironment(environment, null);

        assertThat(environment.getProperty("cimo.banner.provider")).isEqualTo("anthropic");
        assertThat(environment.getProperty("cimo.banner.model")).isEqualTo("default-model");
    }
}
