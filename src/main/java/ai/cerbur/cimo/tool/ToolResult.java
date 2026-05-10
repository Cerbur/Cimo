package ai.cerbur.cimo.tool;

/**
 * 工具执行结果，区分用户可见输出、错误信息和底层进程退出码。
 */
public record ToolResult(
        boolean success,
        String output,
        String error,
        Integer exitCode) {
}
