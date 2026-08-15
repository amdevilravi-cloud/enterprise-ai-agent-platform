package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolResult {

    private String toolName;

    private boolean success;

    private String result;

    private Map<String, Object> data;

    private Map<String, Object> parameters; // Parameters used for this tool execution

    private String errorMessage;

    private long durationMs;

    @Builder.Default
    private List<ArtifactReference> artifacts = new ArrayList<>();
}
