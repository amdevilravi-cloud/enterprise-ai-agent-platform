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
 * Execution - Owned by the runtime.
 * Represents an execution instance created from a PlanningResult.
 * Contains the execution ID, goal, current state, and execution metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Execution {
    private UUID executionId;
    private String goal;
    private ExecutionState state;
    private ExecutionGraph executionGraph;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime completedAt;
}
