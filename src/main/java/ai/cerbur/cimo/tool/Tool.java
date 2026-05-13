package ai.cerbur.cimo.tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 可调用工具的最小协议，也是工具 schema 的事实来源。
 *
 * <p>provider adapter 只能把这里暴露的名称、说明和参数 schema 转换成对应 provider 协议，
 * 不能重新定义工具能力或引入脱离 Tool 的中间规格对象。
 */
public interface Tool {
    String getName();

    String getDescription();

    JsonNode getParameterSchema();

    ToolResult execute(ToolExecutionContext context, JsonNode arguments);
}
