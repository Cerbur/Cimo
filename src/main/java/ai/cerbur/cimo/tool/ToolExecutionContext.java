package ai.cerbur.cimo.tool;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 工具执行时可见的窄上下文，只暴露当前工作目录，避免工具层读取完整 Agent 状态。
 */
public record ToolExecutionContext(Path workingDirectory) {

    public ToolExecutionContext {
        Objects.requireNonNull(workingDirectory, "workingDirectory");
        // 工具层统一拿到绝对归一化路径，后续文件安全校验在此基础上继续收口。
        workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }
}
