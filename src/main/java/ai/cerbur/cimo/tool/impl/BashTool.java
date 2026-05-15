package ai.cerbur.cimo.tool.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolExecutionContext;
import ai.cerbur.cimo.tool.ToolResult;

/**
 * Step 2 的 Bash 工具，负责在用户确认后按真实 shell 语义执行单条命令。
 *
 * <p>当前确认逻辑刻意保持在工具内部的最小实现：每次执行前展示原始命令并等待精确输入
 * {@code y}。后续如果引入统一权限或确认层，应把这段交互门上移，工具只保留执行已确认命令的职责。
 */
@Component
public class BashTool implements Tool {

    private static final int DEFAULT_OUTPUT_LIMIT_BYTES = 100 * 1024;

    private final int timeoutSeconds;
    private final InputStream confirmationInput;
    private final PrintStream confirmationOutput;
    private final int outputLimitBytes;
    private final ObjectNode schema;
    private final long streamCollectionWaitMillis;

    @Autowired
    public BashTool(@Value("${cimo.tool.bash.timeout-seconds:30}") int timeoutSeconds) {
        this(timeoutSeconds, System.in, System.out, DEFAULT_OUTPUT_LIMIT_BYTES, 1_000);
    }

    BashTool(int timeoutSeconds, InputStream confirmationInput, PrintStream confirmationOutput, int outputLimitBytes) {
        this(timeoutSeconds, confirmationInput, confirmationOutput, outputLimitBytes, 1_000);
    }

    BashTool(int timeoutSeconds,
            InputStream confirmationInput,
            PrintStream confirmationOutput,
            int outputLimitBytes,
            long streamCollectionWaitMillis) {
        this.timeoutSeconds = normalizeTimeoutSeconds(timeoutSeconds);
        this.confirmationInput = confirmationInput;
        this.confirmationOutput = confirmationOutput;
        this.outputLimitBytes = normalizeOutputLimitBytes(outputLimitBytes);
        this.streamCollectionWaitMillis = normalizeStreamCollectionWaitMillis(streamCollectionWaitMillis);
        this.schema = buildSchema();
    }

    @Override
    public String getName() {
        return "bash";
    }

    @Override
    public String getDescription() {
        return "Run a bash command in the workspace after explicit user confirmation.";
    }

    @Override
    public JsonNode getParameterSchema() {
        return schema;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
        String command = arguments.path("command").asText("");
        if (command.isBlank()) {
            return new ToolResult(false, "", "Command is required.", null);
        }
        if (!confirm(command)) {
            return new ToolResult(false, "", "cancelled=true\ncommand: " + command, null);
        }

        ProcessBuilder processBuilder = new ProcessBuilder("/bin/bash", "-lc", command);
        processBuilder.directory(context.workingDirectory().toFile());
        ExecutorService streamReaders = Executors.newFixedThreadPool(2);
        try {
            Process process = processBuilder.start();
            InputStream stdoutStream = process.getInputStream();
            InputStream stderrStream = process.getErrorStream();
            Future<CollectedOutput> stdout = streamReaders.submit(() -> collect(stdoutStream));
            Future<CollectedOutput> stderr = streamReaders.submit(() -> collect(stderrStream));
            boolean completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!completed) {
                terminateProcessTree(process);
                closeQuietly(stdoutStream);
                closeQuietly(stderrStream);
                process.waitFor(1, TimeUnit.SECONDS);
                return new ToolResult(false, output(stdout), "Command timed out after " + timeoutSeconds + " seconds.\n"
                        + error(stderr), null);
            }
            int exitCode = process.exitValue();
            return new ToolResult(exitCode == 0, output(stdout), error(stderr), exitCode);
        }
        catch (IOException ex) {
            return new ToolResult(false, "", ex.getMessage(), null);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new ToolResult(false, "", "Command interrupted.", null);
        }
        finally {
            streamReaders.shutdownNow();
        }
    }

    private ObjectNode buildSchema() {
        ObjectMapper objectMapper = new ObjectMapper();
        ObjectNode root = objectMapper.createObjectNode();
        root.put("type", "object");

        ObjectNode propertiesNode = objectMapper.createObjectNode();
        ObjectNode command = objectMapper.createObjectNode();
        command.put("type", "string");
        command.put("description", "Raw bash command to run after user confirmation.");
        propertiesNode.set("command", command);

        root.set("properties", propertiesNode);
        root.set("required", objectMapper.createArrayNode().add("command"));
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 确认门只接受精确的 y，避免空输入、EOF 或含糊回答误触发本地命令。
     */
    private boolean confirm(String command) {
        confirmationOutput.println("请求执行 Bash：" + command);
        confirmationOutput.flush();
        try {
            String answer = new BufferedReader(new InputStreamReader(confirmationInput, StandardCharsets.UTF_8)).readLine();
            return "y".equals(answer);
        }
        catch (IOException ex) {
            return false;
        }
    }

    private CollectedOutput collect(InputStream inputStream) throws IOException {
        byte[] buffer = new byte[4096];
        byte[] collected = new byte[outputLimitBytes];
        int collectedBytes = 0;
        boolean truncated = false;
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            int remaining = outputLimitBytes - collectedBytes;
            if (remaining > 0) {
                int bytesToCopy = Math.min(read, remaining);
                System.arraycopy(buffer, 0, collected, collectedBytes, bytesToCopy);
                collectedBytes += bytesToCopy;
            }
            if (read > remaining) {
                truncated = true;
            }
        }
        return new CollectedOutput(new String(collected, 0, collectedBytes, StandardCharsets.UTF_8), truncated);
    }

    private String output(Future<CollectedOutput> output) {
        return collected(output, streamCollectionWaitMillis).format(outputLimitBytes).stripTrailing();
    }

    private String error(Future<CollectedOutput> output) {
        return output(output);
    }

    private CollectedOutput collected(Future<CollectedOutput> output, long waitMillis) {
        try {
            return output.get(waitMillis, TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new CollectedOutput("Output collection interrupted.", false);
        }
        catch (ExecutionException ex) {
            return new CollectedOutput("Output collection failed: " + ex.getCause().getMessage(), false);
        }
        catch (TimeoutException ex) {
            output.cancel(true);
            return new CollectedOutput("Output collection timed out.", false);
        }
    }

    private static int normalizeTimeoutSeconds(int timeoutSeconds) {
        return timeoutSeconds <= 0 ? 30 : timeoutSeconds;
    }

    private static int normalizeOutputLimitBytes(int outputLimitBytes) {
        return outputLimitBytes <= 0 ? DEFAULT_OUTPUT_LIMIT_BYTES : outputLimitBytes;
    }

    private static long normalizeStreamCollectionWaitMillis(long streamCollectionWaitMillis) {
        return streamCollectionWaitMillis <= 0 ? 1_000 : streamCollectionWaitMillis;
    }

    /**
     * 超时时先杀子进程再杀 shell 本身，避免子进程继续持有管道导致输出收集无法结束。
     */
    private static void terminateProcessTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static void closeQuietly(InputStream inputStream) {
        try {
            inputStream.close();
        }
        catch (IOException ex) {
            // 超时清理阶段的关闭失败不应掩盖真正的 timeout 结果。
        }
    }

    private record CollectedOutput(String text, boolean truncated) {

        String format(int limitBytes) {
            if (!truncated) {
                return text;
            }
            return text + "\n[output truncated at " + limitBytes + " bytes]";
        }
    }
}
