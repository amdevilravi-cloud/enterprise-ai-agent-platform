package com.enterprise.ai.agent.agent_runtime;

import com.enterprise.ai.agent.model.AgentPlan;
import com.enterprise.ai.agent.model.ArtifactReference;
import com.enterprise.ai.agent.model.Observation;
import com.enterprise.ai.agent.model.ToolResult;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
    private List<ArtifactReference> artifactReferences;  // References to artifacts, not full objects
    private List<String> reviews;  // Review history
    private List<String> failures;  // Failure tracking
    private List<String> outputs;  // General outputs
    private Map<String, Object> variables;
    private List<String> knowledgeReferences;
    private List<RetryHistory> retryHistory;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    
    // Context size limits to prevent memory bloat (configurable via application.properties)
    @Builder.Default
    private int maxObservations = 50;
    
    @Builder.Default
    private int maxToolResults = 50;
    
    @Builder.Default
    private int maxKnowledgeReferences = 100;
    
    @Builder.Default
    private int maxRetryHistory = 20;
    
    @Builder.Default
    private int maxVariables = 100;

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
                .artifactReferences(new ArrayList<>())
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
        // Prune if exceeding limit
        if (this.observations.size() > maxObservations) {
            this.observations.remove(0); // Remove oldest
            log.debug("Pruned oldest observation to maintain limit of {}", maxObservations);
        }
        this.updatedAt = LocalDateTime.now();
        log.debug("Added observation to context {}: {}", executionId, observation.getContent());
    }

    public void addToolResult(ToolResult result) {
        this.toolResults.add(result);
        // Prune if exceeding limit
        if (this.toolResults.size() > maxToolResults) {
            this.toolResults.remove(0); // Remove oldest
            log.debug("Pruned oldest tool result to maintain limit of {}", maxToolResults);
        }
        this.updatedAt = LocalDateTime.now();
        log.debug("Added tool result to context {}: tool={}, success={}", executionId, result.getToolName(), result.isSuccess());
    }

    public void setVariable(String key, Object value) {
        this.variables.put(key, value);
        // Prune if exceeding limit
        if (this.variables.size() > maxVariables) {
            // Remove oldest entry (first key)
            this.variables.keySet().stream().findFirst().ifPresent(this.variables::remove);
            log.debug("Pruned oldest variable to maintain limit of {}", maxVariables);
        }
        this.updatedAt = LocalDateTime.now();
        log.debug("Set variable in context {}: {}={}", executionId, key, value);
    }

    public Object getVariable(String key) {
        return this.variables.get(key);
    }

    public void addKnowledgeReference(String reference) {
        this.knowledgeReferences.add(reference);
        // Prune if exceeding limit
        if (this.knowledgeReferences.size() > maxKnowledgeReferences) {
            this.knowledgeReferences.remove(0); // Remove oldest
            log.debug("Pruned oldest knowledge reference to maintain limit of {}", maxKnowledgeReferences);
        }
        this.updatedAt = LocalDateTime.now();
        log.debug("Added knowledge reference to context {}: {}", executionId, reference);
    }

    public void addArtifactReference(ArtifactReference reference) {
        this.artifactReferences.add(reference);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added artifact reference to context {}: type={}, name={}, version={}", 
                executionId, reference.getType(), reference.getName(), reference.getVersion());
    }

    public boolean hasArtifactType(String type) {
        return this.artifactReferences.stream()
                .anyMatch(ref -> type.equalsIgnoreCase(ref.getType()));
    }

    public List<ArtifactReference> getArtifactReferences() {
        return this.artifactReferences;
    }

    public void addCompletedMilestone(String milestone) {
        this.completedMilestones.add(milestone);
        this.updatedAt = LocalDateTime.now();
        log.debug("Added completed milestone to context {}: {}", executionId, milestone);
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
        // Prune if exceeding limit
        if (this.retryHistory.size() > maxRetryHistory) {
            this.retryHistory.remove(0); // Remove oldest
            log.debug("Pruned oldest retry history to maintain limit of {}", maxRetryHistory);
        }
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
        if (milestone == null || milestone.isEmpty()) {
            log.warn("Attempted to set null or empty milestone in context {}", executionId);
            return;
        }

        // Prevent milestone rollback - milestones must be monotonic
        if (this.currentMilestone != null && !this.currentMilestone.isEmpty()) {
            // Check if this is a rollback attempt
            if (isRollback(this.currentMilestone, milestone)) {
                log.error("MILESTONE ROLLBACK PREVENTED: Cannot go from '{}' to '{}'. Milestones must progress forward.", 
                        this.currentMilestone, milestone);
                return; // Reject the rollback
            }
            
            // Only add to completed if actually changing
            if (!this.currentMilestone.equals(milestone)) {
                this.completedMilestones.add(this.currentMilestone);
                log.debug("Added milestone '{}' to completed list", this.currentMilestone);
            }
        }
        
        this.currentMilestone = milestone;
        this.updatedAt = LocalDateTime.now();
        log.debug("Set current milestone in context {}: {}", executionId, milestone);
    }

    /**
     * Check if a milestone transition would be a rollback
     * Milestones must progress forward, never backward
     */
    private boolean isRollback(String current, String proposed) {
        if (current.equals(proposed)) {
            return false; // Same milestone is not a rollback
        }
        
        // Define milestone order
        List<String> milestoneOrder = Arrays.asList(
            "Collect Information",
            "Collect Overview",
            "Gather Detailed Information",
            "Collect Data",
            "Analyze Information",
            "Analyze Findings",
            "Analyze Data",
            "Generate Outline",
            "Synthesize Research",
            "Generate Insights",
            "Write Document",
            "Create Report",
            "Review Document",
            "Complete"
        );
        
        int currentIndex = milestoneOrder.indexOf(current);
        int proposedIndex = milestoneOrder.indexOf(proposed);
        
        if (currentIndex == -1 || proposedIndex == -1) {
            // Unknown milestones - use string comparison as fallback
            return current.compareTo(proposed) > 0;
        }
        
        return proposedIndex < currentIndex;
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
        this.artifactReferences.clear();
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
