package com.enterprise.ai.agent.tools;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.ToolRequest;
import com.enterprise.ai.agent.model.ToolResult;

public interface Tool {
    String name();
    String description();
    ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager);
}
