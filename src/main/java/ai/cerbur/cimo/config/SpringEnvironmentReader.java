package ai.cerbur.cimo.config;

import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * 提供按需读取 Spring Environment 的极窄入口，避免把临时诊断开关固化到业务组件状态中。
 */
@Component
public class SpringEnvironmentReader implements EnvironmentAware {

    private static volatile Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        SpringEnvironmentReader.environment = environment;
    }

    /**
     * 读取布尔配置；Spring 上下文尚未注入时返回调用方提供的默认值。
     */
    public static boolean getBoolean(String propertyName, boolean defaultValue) {
        Environment current = environment;
        if (current == null) {
            return defaultValue;
        }
        return current.getProperty(propertyName, Boolean.class, defaultValue);
    }

    /**
     * 测试中替换 Environment，避免为了验证单个配置读取而启动完整 Spring 上下文。
     */
    public static void useEnvironmentForTests(Environment environment) {
        SpringEnvironmentReader.environment = environment;
    }
}
