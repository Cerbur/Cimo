package ai.cerbur.cimo.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 可调用工具的最小协议：向模型暴露名称、说明、参数 schema，并执行结构化参数。
 */
public interface Tool {
    String getName();

    String getDescription();

    JsonNode getParameterSchema();

    ToolResult execute(JsonNode arguments);
}
