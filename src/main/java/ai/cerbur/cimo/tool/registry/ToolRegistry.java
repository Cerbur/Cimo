package ai.cerbur.cimo.tool.registry;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import ai.cerbur.cimo.tool.Tool;

@Component
public class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(List<Tool> tools) {
        this.tools = tools.stream().collect(Collectors.toUnmodifiableMap(Tool::getName, Function.identity()));
    }

    public Optional<Tool> getTool(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Collection<Tool> allTools() {
        return tools.values();
    }
}
