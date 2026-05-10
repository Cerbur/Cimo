package ai.cerbur.cimo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Cimo Spring Boot 启动入口，启动后由 CLI ApplicationRunner 接管交互式会话。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CimoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CimoApplication.class, args);
    }

}
