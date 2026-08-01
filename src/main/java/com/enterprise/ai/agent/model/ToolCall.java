package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * ToolCall - Represents a tool invocation during execution.
 * Contains the tool name, parameters, result, and execution metadata.
 * Owned by the runtime for tracking tool execution and results.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCall {
    private UUID callId;
    private UUID actionId;
    private String toolName;
    private Map<String, Object> parameters;
    private String result;
    private boolean success;
    private String errorMessage;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private long durationMs;
}
