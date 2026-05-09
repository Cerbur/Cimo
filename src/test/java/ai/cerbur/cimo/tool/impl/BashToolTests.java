package ai.cerbur.cimo.tool.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;

import ai.cerbur.cimo.tool.ToolResult;

class BashToolTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void schemaOnlyAllowsEcho() {
        BashTool tool = new BashTool(30);

        assertThat(tool.getParameterSchema()
                .path("properties")
                .path("command")
                .path("enum"))
                .hasSize(1);
        assertThat(tool.getParameterSchema()
                .path("properties")
                .path("command")
                .path("enum")
                .get(0)
                .asText())
                .isEqualTo("echo");
    }

    @Test
    void rejectsCommandsOtherThanEcho() {
        BashTool tool = new BashTool(30);
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("command", "pwd");
        arguments.putArray("args");

        ToolResult result = tool.execute(arguments);

        assertThat(result.success()).isFalse();
        assertThat(result.error()).isEqualTo("Command is not allowed: pwd");
    }

    @Test
    void executesEcho() {
        BashTool tool = new BashTool(30);
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("command", "echo");
        arguments.putArray("args").add("hello");

        ToolResult result = tool.execute(arguments);

        assertThat(result.success()).isTrue();
        assertThat(result.output()).isEqualTo("hello");
        assertThat(result.exitCode()).isEqualTo(0);
    }
}
