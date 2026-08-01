package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ExecutionStep - Represents a single step in an execution.
 * Tracks the step number, action, status, and timing information.
 * Owned by the runtime for execution tracking and crash recovery.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionStep {
    private UUID stepId;
    private int stepNumber;
    private UUID actionId;
    private String actionType;
    private StepStatus status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private String errorMessage;
    private long durationMs;

    public enum StepStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED
    }
}
