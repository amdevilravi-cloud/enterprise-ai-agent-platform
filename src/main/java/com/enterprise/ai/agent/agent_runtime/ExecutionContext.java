package com.enterprise.ai.agent.agent_runtime;

import com.enterprise.ai.agent.model.AgentPlan;
import com.enterprise.ai.agent.model.Artifact;
import com.enterprise.ai.agent.model.Observation;
import com.enterprise.ai.agent.model.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
public class ExecutionContext {

    private UUID executionId;
    private String goal;
    private AgentPlan plan;
    private int currentStep;
    private String currentMilestone;
    private List<String> completedMilestones;
    private List<Observation> observations;
    private List<ToolResult> toolResults;
    private List<Artifact> artifacts;
    private List<String> reviews;  // Review history
    private List<String> failures;  // Failure tracking
    private List<String> outputs;  // General outputs
    private Map<String, Object> variables;
    private List<String> knowledgeReferences;
    private List<RetryHistory> retryHistory;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static ExecutionContext create(String goal) {
        UUID executionId = UUID.randomUUID();
        return ExecutionContext.builder()
                .executionId(executionId)
                .goal(goal)
                .currentStep(0)
                .currentMilestone("")
                .completedMilestones(new ArrayList<>())
                .observations(new ArrayList<>())
                .toolResults(new ArrayList<>())
                .artifacts(new ArrayList<>())
                .reviews(new ArrayList<>())
                .failures(new ArrayList<>())
                .outputs(new ArrayList<>())
                .variables(new HashMap<>())
                .knowledgeReferences(new ArrayList<>())
                .retryHistory(new ArrayList<>())
                .metadata(new HashMap<>())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public void addObservation(Observation observation) {
        this.observations.add(observation);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added observation to context {}: {}", executionId, observation.getContent());
    }

    public void addToolResult(ToolResult result) {
        this.toolResults.add(result);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added tool result to context {}: tool={}, success={}", executionId, result.getToolName(), result.isSuccess());
    }

    public void setVariable(String key, Object value) {
        this.variables.put(key, value);
        this.updatedAt = LocalDateTime.now();
        log.debug("Set variable in context {}: {}={}", executionId, key, value);
    }

    public Object getVariable(String key) {
        return this.variables.get(key);
    }

    public void addKnowledgeReference(String reference) {
        this.knowledgeReferences.add(reference);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added knowledge reference to context {}: {}", executionId, reference);
    }

    public void addArtifact(Artifact artifact) {
        this.artifacts.add(artifact);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added artifact to context {}: type={}, name={}", executionId, artifact.getType(), artifact.getName());
    }

    public void addCompletedMilestone(String milestone) {
        this.completedMilestones.add(milestone);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added completed milestone to context {}: {}", executionId, milestone);
    }

    public List<Artifact> getArtifacts() {
        return this.artifacts;
    }
    
    public void addReview(String review) {
        this.reviews.add(review);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added review to context {}: {}", executionId, review);
    }
    
    public void addFailure(String failure) {
        this.failures.add(failure);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added failure to context {}: {}", executionId, failure);
    }
    
    public void addOutput(String output) {
        this.outputs.add(output);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added output to context {}: {}", executionId, output);
    }

    public void addRetry(RetryHistory retry) {
        this.retryHistory.add(retry);
        this.updatedAt = LocalDateTime.now();
    }

    public void setMetadata(String key, Object value) {
        this.metadata.put(key, value);
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementStep() {
        this.currentStep++;
        this.updatedAt = LocalDateTime.now();
        log.debug("Incremented step in context {}: now at step {}", executionId, currentStep);
    }

    public void setCurrentMilestone(String milestone) {
        if (this.currentMilestone != null && !this.currentMilestone.isEmpty() 
            && !this.currentMilestone.equals(milestone)) {
            // Milestone changed, add old milestone to completed
            this.completedMilestones.add(this.currentMilestone);
            log.debug("Completed milestone in context {}: {}", executionId, this.currentMilestone);
        }
        this.currentMilestone = milestone;
        this.updatedAt = LocalDateTime.now();
        log.debug("Set current milestone in context {}: {}", executionId, milestone);
    }

    public String getCurrentMilestone() {
        return this.currentMilestone;
    }

    public List<String> getCompletedMilestones() {
        return this.completedMilestones;
    }

    /**
     * Save state for resumption (checkpoint)
     * Persistence is now handled by ExecutionContextManager
     */
    public void checkpoint() {
        this.updatedAt = LocalDateTime.now();
        log.info("Checkpoint triggered for execution context: {}", executionId);
        // Actual persistence is handled by ExecutionContextManager.updateContext()
    }

    /**
     * Resume from checkpoint
     * Loading is handled by ExecutionContextManager.getContext()
     */
    public void resume(String executionId) {
        log.info("Resume triggered for execution context: {}", executionId);
        // Actual loading is handled by ExecutionContextManager.getContext()
    }

    /**
     * Clean up after completion
     */
    public void discard() {
        log.info("Discarding execution context: {}", executionId);
        this.observations.clear();
        this.toolResults.clear();
        this.artifacts.clear();
        this.variables.clear();
        this.knowledgeReferences.clear();
        this.retryHistory.clear();
        this.metadata.clear();
        // TODO: Remove from database if persisted
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RetryHistory {
        private String stepId;
        private int attempt;
        private String reason;
        private LocalDateTime timestamp;
    }
}
