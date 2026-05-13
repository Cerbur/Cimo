package ai.cerbur.cimo.client.anthropic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.test.util.ReflectionTestUtils;

import ai.cerbur.cimo.tool.Tool;
import ai.cerbur.cimo.tool.ToolExecutionContext;
import ai.cerbur.cimo.tool.ToolResult;

class SpringAiAnthropicClientToolSchemaTests {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void exposesToolSchemaFromToolDefinitionWithoutIntermediateSpec() throws Exception {
        Tool tool = new SchemaOnlyTool();
        SpringAiAnthropicClient client = new SpringAiAnthropicClient(null, objectMapper, 1024);

        ToolCallback callback = toToolCallback(client, tool);
        ToolDefinition definition = callback.getToolDefinition();

        assertThat(definition.name()).isEqualTo(tool.getName());
        assertThat(definition.description()).isEqualTo(tool.getDescription());
        assertThat(objectMapper.readTree(definition.inputSchema())).isEqualTo(tool.getParameterSchema());
    }

    @Test
    void callbackDoesNotExecuteToolsInsideProviderAdapter() {
        ToolCallback callback = toToolCallback(
                new SpringAiAnthropicClient(null, objectMapper, 1024),
                new SchemaOnlyTool());

        assertThatThrownBy(() -> callback.call("{}"))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessage("Cimo AgentLoop executes tools explicitly.");
    }

    private ToolCallback toToolCallback(SpringAiAnthropicClient client, Tool tool) {
        return (ToolCallback) ReflectionTestUtils.invokeMethod(client, "toToolCallback", tool);
    }

    private class SchemaOnlyTool implements Tool {

        private final ObjectNode schema = buildSchema();

        @Override
        public String getName() {
            return "schema_only";
        }

        @Override
        public String getDescription() {
            return "Exposes a provider-neutral schema.";
        }

        @Override
        public JsonNode getParameterSchema() {
            return schema;
        }

        @Override
        public ToolResult execute(ToolExecutionContext context, JsonNode arguments) {
            throw new AssertionError("Provider adapter must not execute tools.");
        }

        private ObjectNode buildSchema() {
            ObjectNode root = objectMapper.createObjectNode();
            root.put("type", "object");
            ObjectNode properties = objectMapper.createObjectNode();
            ObjectNode path = objectMapper.createObjectNode();
            path.put("type", "string");
            path.put("description", "Path inside the current workspace.");
            properties.set("path", path);
            root.set("properties", properties);
            root.putArray("required").add("path");
            root.put("additionalProperties", false);
            return root;
        }
    }
}
