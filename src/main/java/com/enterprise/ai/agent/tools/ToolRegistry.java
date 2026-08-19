package com.enterprise.ai.agent.tools;

import com.enterprise.ai.agent.workflow.ToolSchema;
import java.util.Map;

public interface ToolRegistry {
    Tool get(String toolName);
    void register(Tool tool);
    boolean hasTool(String toolName);
    
    /**
     * Get the schema for a specific tool.
     * Returns null if the tool is not registered or schema is not available.
     */
    default ToolSchema getToolSchema(String toolName) {
        return null;
    }
    
    /**
     * Get all available tool schemas.
     * Returns an empty map if no schemas are available.
     */
    default Map<String, ToolSchema> getAllToolSchemas() {
        return Map.of();
    }
}
