package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ExecutionState - Mutable state during execution.
 * Owned by the runtime and updated as actions are executed.
 * Enables crash recovery by tracking completed actions and current position.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionState {
    @Builder.Default
    private int currentStep = 0;
    @Builder.Default
    private List<AgentAction> completedActions = new ArrayList<>();
    @Builder.Default
    private List<AgentAction> pendingActions = new ArrayList<>();
    @Builder.Default
    private List<Observation> observations = new ArrayList<>();
    @Builder.Default
    private List<ToolResult> toolResults = new ArrayList<>();
    @Builder.Default
    private Map<String, Object> variables = new HashMap<>();
    @Builder.Default
    private List<String> knowledgeReferences = new ArrayList<>();
    @Builder.Default
    private int retryCount = 0;
    private String lastError;

    public void addCompletedAction(AgentAction action) {
        this.completedActions.add(action);
    }

    public void addObservation(Observation observation) {
        this.observations.add(observation);
    }

    public void addToolResult(ToolResult result) {
        this.toolResults.add(result);
    }

    public void setVariable(String key, Object value) {
        this.variables.put(key, value);
    }

    public void addKnowledgeReference(String reference) {
        this.knowledgeReferences.add(reference);
    }

    public void incrementStep() {
        this.currentStep++;
    }

    public void incrementRetry() {
        this.retryCount++;
    }

    public boolean canRetry(int maxRetries) {
        return this.retryCount < maxRetries;
    }
}
