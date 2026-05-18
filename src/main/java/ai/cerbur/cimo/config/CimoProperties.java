package ai.cerbur.cimo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cimo 一级运行配置，承载跨入口、Agent Loop 和 provider 选择共享的运行语义。
 */
@ConfigurationProperties(prefix = "cimo")
public record CimoProperties(
        String provider,
        boolean debug,
        String workDir,
        int maxToolRounds) {

    public static final int DEFAULT_MAX_TOOL_ROUNDS = 128;

    public CimoProperties {
        provider = normalizeProvider(provider);
        workDir = normalizeWorkDir(workDir);
        if (maxToolRounds <= 0) {
            maxToolRounds = DEFAULT_MAX_TOOL_ROUNDS;
        }
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "anthropic";
        }
        return provider.trim().toLowerCase();
    }

    private static String normalizeWorkDir(String workDir) {
        if (workDir == null || workDir.isBlank()) {
            return System.getProperty("user.dir");
        }
        return workDir;
    }
}
