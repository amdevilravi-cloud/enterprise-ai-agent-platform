package com.enterprise.ai.agent.model;

import com.enterprise.ai.agent.graph.ExecutionGraph;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Execution - Represents a complete execution instance with all metadata.
 * Contains goal, planner output, tool calls, observations, artifacts, and final answer.
 * This is the comprehensive execution model returned to the UI.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentResponse {

    private UUID executionId;
    private String goal;
    private String answer;
    private boolean completed;
    private String status;
    private ExecutionStatus executionStatus;
    private String errorMessage;

    // Planner output
    private String plannerReasoning;
    private String plannerDecision;
    private String plannerNextStep;
    private Double plannerConfidence;

    // Execution data
    private List<Action> actionsTaken;
    private List<ToolCall> toolCalls;
    private List<Observation> observations;
    private List<Artifact> artifacts;
    private ExecutionGraph executionGraph;

    // Timing
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
}
