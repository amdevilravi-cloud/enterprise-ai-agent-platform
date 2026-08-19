package com.enterprise.ai.agent.tools;

import com.enterprise.ai.agent.workflow.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class ToolRegistryImpl implements ToolRegistry {

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolSchema> toolSchemas = new ConcurrentHashMap<>();

    public ToolRegistryImpl() {
        log.info("ToolRegistry initialized");
    }

    @Override
    public Tool get(String toolName) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            log.warn("Tool not found: {}", toolName);
        }
        return tool;
    }

    @Override
    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.info("Registered tool: {} - {}", tool.name(), tool.description());
    }

    @Override
    public boolean hasTool(String toolName) {
        return tools.containsKey(toolName);
    }

    @Override
    public ToolSchema getToolSchema(String toolName) {
        return toolSchemas.get(toolName);
    }

    @Override
    public Map<String, ToolSchema> getAllToolSchemas() {
        return new HashMap<>(toolSchemas);
    }

    /**
     * Register a tool schema for a registered tool.
     * This allows ToolRegistry to be the single source of truth for tool schemas.
     */
    public void registerToolSchema(String toolName, ToolSchema schema) {
        toolSchemas.put(toolName, schema);
        log.info("Registered tool schema: {}", toolName);
    }

    public int getToolCount() {
        return tools.size();
    }
}
