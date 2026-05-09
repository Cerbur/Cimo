package ai.cerbur.cimo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "cimo.cli.enabled=false",
        "cimo.anthropic.api-key=test-key",
        "cimo.anthropic.model=claude-sonnet-4-20250514",
        "cimo.anthropic.base-url=https://api.anthropic.com"
})
class CimoApplicationTests {

    @Test
    void contextLoads() {
    }

}
