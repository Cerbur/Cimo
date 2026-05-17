package ai.cerbur.cimo.entry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ResourceBanner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

class CimoBannerResourceTests {

    @Test
    void resourceBannerRendersDisplayPropertiesWithoutSensitiveSettings() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("cimo.banner.provider", "anthropic")
                .withProperty("cimo.banner.model", "test-model")
                .withProperty("cimo.anthropic.api-key", "secret-key")
                .withProperty("cimo.anthropic.base-url", "https://example.invalid");

        String banner = printBanner(environment);

        assertThat(banner)
                .contains("Cimo Agent Harness")
                .contains("Provider : anthropic")
                .contains("Model    : test-model")
                .doesNotContain("secret-key")
                .doesNotContain("https://example.invalid")
                .doesNotContain("api-key")
                .doesNotContain("base-url");
    }

    private static String printBanner(MockEnvironment environment) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        new ResourceBanner(new ClassPathResource("banner.txt")).printBanner(
                environment,
                CimoBannerResourceTests.class,
                new PrintStream(output, true, StandardCharsets.UTF_8));
        return output.toString(StandardCharsets.UTF_8);
    }
}
