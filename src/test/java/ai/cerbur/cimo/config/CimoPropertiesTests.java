package ai.cerbur.cimo.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class CimoPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class);

    @Test
    void bindsDefaultCimoRuntimeProperties() {
        contextRunner.run(context -> {
            CimoProperties properties = context.getBean(CimoProperties.class);

            assertThat(properties.provider()).isEqualTo("anthropic");
            assertThat(properties.debug()).isFalse();
            assertThat(properties.workDir()).isEqualTo(System.getProperty("user.dir"));
            assertThat(properties.maxToolRounds()).isEqualTo(5);
        });
    }

    @Test
    void bindsExplicitCimoRuntimeProperties() {
        contextRunner
                .withPropertyValues(
                        "cimo.provider=OpenAI",
                        "cimo.debug=true",
                        "cimo.work-dir=/tmp/cimo",
                        "cimo.max-tool-rounds=9")
                .run(context -> {
                    CimoProperties properties = context.getBean(CimoProperties.class);

                    assertThat(properties.provider()).isEqualTo("openai");
                    assertThat(properties.debug()).isTrue();
                    assertThat(properties.workDir()).isEqualTo("/tmp/cimo");
                    assertThat(properties.maxToolRounds()).isEqualTo(9);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CimoProperties.class)
    static class TestConfig {
    }
}
