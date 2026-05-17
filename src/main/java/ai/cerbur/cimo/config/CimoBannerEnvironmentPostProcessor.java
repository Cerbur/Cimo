package ai.cerbur.cimo.config;

import java.util.Locale;
import java.util.Map;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 在 Spring Boot 打印资源 banner 前准备展示用配置，避免 banner 模板承担条件判断逻辑。
 */
public class CimoBannerEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    private static final String PROPERTY_SOURCE_NAME = "cimoBanner";

    private static final String MODEL_PLACEHOLDER = "<model not configured>";

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String provider = normalizeProvider(environment.getProperty("cimo.provider"));
        String model = resolveModel(environment, provider);
        MapPropertySource propertySource = new MapPropertySource(PROPERTY_SOURCE_NAME, Map.of(
                "cimo.banner.provider", provider,
                "cimo.banner.model", model));
        environment.getPropertySources().addFirst(propertySource);
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "anthropic";
        }
        return provider.trim().toLowerCase(Locale.ROOT);
    }

    private static String resolveModel(ConfigurableEnvironment environment, String provider) {
        String model = switch (provider) {
            case "anthropic" -> environment.getProperty("cimo.anthropic.model");
            case "openai" -> environment.getProperty("cimo.openai.model");
            default -> environment.getProperty("cimo." + provider + ".model");
        };
        if (model == null || model.isBlank()) {
            return MODEL_PLACEHOLDER;
        }
        return model.trim();
    }
}
