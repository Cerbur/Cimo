package ai.cerbur.cimo.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "cimo")
public record CimoProperties(
        String provider,
        String workDir,
        Tool tool,
        Agent agent) {

    public CimoProperties {
        provider = provider == null ? "anthropic" : provider;
        workDir = workDir == null || workDir.isBlank() ? System.getProperty("user.dir") : workDir;
        tool = tool == null ? new Tool(new Bash(30, List.of("echo"))) : tool;
        agent = agent == null ? new Agent(5) : agent;
    }

    public record Tool(Bash bash) {
        public Tool {
            bash = bash == null ? new Bash(30, List.of("echo")) : bash;
        }
    }

    public record Bash(int timeoutSeconds, List<String> allowedCommands) {
        public Bash {
            timeoutSeconds = timeoutSeconds <= 0 ? 30 : timeoutSeconds;
            allowedCommands = allowedCommands == null || allowedCommands.isEmpty() ? List.of("echo") : List.copyOf(allowedCommands);
        }
    }

    public record Agent(int maxToolRounds) {
        public Agent {
            maxToolRounds = maxToolRounds <= 0 ? 5 : maxToolRounds;
        }
    }
}
