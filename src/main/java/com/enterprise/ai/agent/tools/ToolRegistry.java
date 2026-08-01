package com.enterprise.ai.agent.tools;

public interface ToolRegistry {
    Tool get(String toolName);
    void register(Tool tool);
}
