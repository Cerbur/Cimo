package ai.cerbur.cimo.entry;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.springframework.beans.factory.annotation.Autowired;
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

/**
 * JLine CLI 入口，负责启动自然语言 REPL，并把 Agent 事件转换成人类可读的终端输出。
 */
@Component
@ConditionalOnProperty(prefix = "cimo.cli", name = "enabled", havingValue = "true", matchIfMissing = true)
public class CliAgentEntry implements AgentEntry, ApplicationRunner, AgentEventHandler {

    @Autowired
    private AgentLoop agentLoop;

    @Autowired
    private CimoProperties cimoProperties;

    @Autowired
    private ToolRegistry toolRegistry;

    private boolean responseLineOpen;

    @Override
    public void run(ApplicationArguments args) {
        start();
    }

    @Override
    public void start() {
        // 入口层负责把 Spring 配置组装成 AgentContext；AgentLoop 不直接读取 Spring Environment。
        agentLoop.start(new AgentContext(
                cimoProperties.workDir(),
                CimoPrompts.STEP_1_SYSTEM_PROMPT,
                toolRegistry,
                cimoProperties.maxToolRounds()), this);
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
                // Step 2 会把本地指令抽成 handler；这里先保留 Step 1 的兼容退出语义。
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
            case AgentEvent.Thinking thinking -> {
                ensureResponseLineClosed();
                System.out.println("Thinking: " + thinking.message());
            }
            case AgentEvent.ToolCall toolCall -> {
                ensureResponseLineClosed();
                System.out.println(formatToolCall(toolCall));
            }
            case AgentEvent.ToolResult toolResult -> {
                ensureResponseLineClosed();
                System.out.println(formatToolResult(toolResult));
            }
            case AgentEvent.Response response -> {
                System.out.print(response.content());
                responseLineOpen = !response.content().endsWith("\n");
            }
            case AgentEvent.Error error -> {
                ensureResponseLineClosed();
                System.out.println("Error: " + error.message());
            }
        }
        System.out.flush();
    }

    private String formatToolCall(AgentEvent.ToolCall toolCall) {
        String summary = "Tool: " + toolCall.toolName() + formatToolArguments(toolCall.args());
        if (!cimoProperties.debug()) {
            return summary;
        }
        return summary + " (raw: " + toolCall.args() + ")";
    }

    private String formatToolResult(AgentEvent.ToolResult toolResult) {
        return "Result: " + toolResult.toolName() + ": " + toolResult.result();
    }

    private void ensureResponseLineClosed() {
        if (responseLineOpen) {
            System.out.println();
            responseLineOpen = false;
        }
    }

    /**
     * 默认 CLI 输出面向用户，只把 Step 1 的结构化 bash 参数压缩成人类可读命令摘要。
     */
    private static String formatToolArguments(JsonNode args) {
        if (args == null || args.isNull()) {
            return "";
        }
        String command = args.path("command").asText("");
        if (command.isBlank()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        parts.add(command);
        JsonNode commandArgs = args.path("args");
        if (commandArgs.isArray()) {
            for (JsonNode commandArg : commandArgs) {
                parts.add(commandArg.asText());
            }
        }
        return " " + String.join(" ", parts);
    }
}
