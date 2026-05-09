package ai.cerbur.cimo.tool;

public record ToolResult(
        boolean success,
        String output,
        String error,
        Integer exitCode) {
}
