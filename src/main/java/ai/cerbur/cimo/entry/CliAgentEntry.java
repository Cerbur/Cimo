package ai.cerbur.cimo.entry;

import java.io.IOException;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import ai.cerbur.cimo.agent.AgentContext;
import ai.cerbur.cimo.agent.AgentLoop;
import ai.cerbur.cimo.config.CimoProperties;
import ai.cerbur.cimo.entry.event.AgentEvent;
import ai.cerbur.cimo.entry.event.AgentEventHandler;
import ai.cerbur.cimo.prompt.CimoPrompts;
import ai.cerbur.cimo.tool.registry.ToolRegistry;

@Component
@ConditionalOnProperty(prefix = "cimo.cli", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CliAgentEntry implements AgentEntry, ApplicationRunner, AgentEventHandler {

    private final AgentLoop agentLoop;
    private final CimoProperties properties;
    private final ToolRegistry toolRegistry;

    public CliAgentEntry(AgentLoop agentLoop, CimoProperties properties, ToolRegistry toolRegistry) {
        this.agentLoop = agentLoop;
        this.properties = properties;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    @Override
    public void start() {
        agentLoop.start(new AgentContext(
                properties.workDir(),
                CimoPrompts.STEP_1_SYSTEM_PROMPT,
                toolRegistry,
                properties.agent().maxToolRounds()), this);
        printBanner();
        try (Terminal terminal = TerminalBuilder.builder().system(true).build()) {
            LineReader reader = LineReaderBuilder.builder().terminal(terminal).build();
            while (true) {
                String line;
                try {
                    line = reader.readLine("> ");
                }
                catch (UserInterruptException | EndOfFileException ex) {
                    break;
                }
                if (line == null || line.isBlank()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(line.trim()) || "quit".equalsIgnoreCase(line.trim())) {
                    break;
                }
                submitInput(line);
            }
        }
        catch (IOException ex) {
            onEvent(new AgentEvent.Error("CLI 初始化失败: " + ex.getMessage()));
        }
        shutdown();
        System.out.println("Bye!");
    }

    @Override
    public void submitInput(String input) {
        agentLoop.processInput(input);
    }

    @Override
    public void shutdown() {
        agentLoop.shutdown();
    }

    @Override
    public void onEvent(AgentEvent event) {
        switch (event) {
            case AgentEvent.Thinking thinking -> System.out.println("Thinking: " + thinking.message());
            case AgentEvent.ToolCall toolCall -> System.out.println("Tool: " + toolCall.toolName() + " " + toolCall.args());
            case AgentEvent.ToolResult toolResult -> System.out.println("Result: " + toolResult.result());
            case AgentEvent.Response response -> System.out.print(response.content());
            case AgentEvent.Error error -> System.out.println("Error: " + error.message());
            case AgentEvent.StatusChange ignored -> {
            }
        }
        if (event instanceof AgentEvent.Response response && response.content().endsWith("\n")) {
            return;
        }
        if (event instanceof AgentEvent.ToolResult || event instanceof AgentEvent.Error) {
            System.out.flush();
        }
    }

    private void printBanner() {
        System.out.println("""

                  Cimo Agent
                  Type 'exit' to quit
                """);
    }
}
