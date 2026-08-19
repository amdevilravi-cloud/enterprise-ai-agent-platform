package com.enterprise.ai.agent.agent_runtime;

import com.enterprise.ai.agent.config.CorrelationIdUtil;
import com.enterprise.ai.agent.persistence.ExecutionContextEntity;
import com.enterprise.ai.agent.persistence.ExecutionContextRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
@Slf4j
public class ExecutionContextManager {

    private final Map<UUID, ExecutionContext> activeContexts = new ConcurrentHashMap<>();
    private final Map<UUID, AtomicInteger> updateCounters = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> previousCompletedMilestoneCounts = new ConcurrentHashMap<>();
    private final ExecutionContextRepository repository;
    private final ObjectMapper objectMapper;
    
    @Value("${agent.context.batch-save-threshold:10}")
    private int batchSaveThreshold;

    public ExecutionContextManager(ExecutionContextRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public ExecutionContext createContext(String goal) {
        ExecutionContext context = ExecutionContext.create(goal);
        activeContexts.put(context.getExecutionId(), context);
        updateCounters.put(context.getExecutionId(), new AtomicInteger(0));
        
        // Persist to database
        saveContext(context);
        
        log.info("Created execution context: {} for goal: {}", context.getExecutionId(), goal);
        return context;
    }

    public ExecutionContext getContext(UUID executionId) {
        // Check active contexts first
        ExecutionContext context = activeContexts.get(executionId);
        if (context != null) {
            return context;
        }
        
        // Try to load from database
        return loadContext(executionId);
    }

    public void updateContext(ExecutionContext context) {
        activeContexts.put(context.getExecutionId(), context);
        
        // Increment update counter
        AtomicInteger counter = updateCounters.computeIfAbsent(context.getExecutionId(), k -> new AtomicInteger(0));
        int updateCount = counter.incrementAndGet();
        
        // Batch save: save every N updates or on milestone completion
        boolean shouldSave = (updateCount % batchSaveThreshold == 0) || 
                           isMilestoneCompletion(context);
        
        if (shouldSave) {
            saveContext(context);
            log.debug("Batch saved execution context: {} (update count: {})", context.getExecutionId(), updateCount);
        } else {
            log.debug("Deferred save for execution context: {} (update count: {})", context.getExecutionId(), updateCount);
        }
    }
    
    /**
     * Force immediate save (for critical updates)
     */
    public void forceSaveContext(ExecutionContext context) {
        activeContexts.put(context.getExecutionId(), context);
        saveContext(context);
        log.debug("Force saved execution context: {}", context.getExecutionId());
    }

    public void discardContext(UUID executionId) {
        ExecutionContext context = activeContexts.remove(executionId);
        updateCounters.remove(executionId); // Clean up counter
        previousCompletedMilestoneCounts.remove(executionId); // Clean up milestone tracking
        if (context != null) {
            context.discard();
            log.info("Discarded execution context: {}", executionId);
        }
        
        // P1: Don't auto-mark as completed - let caller decide based on outcome
        // markContextCompleted(executionId);
    }
    
    /**
     * Check if this update represents a milestone completion
     * Tracks changes in completed milestone count to avoid disabling batching after first milestone
     */
    private boolean isMilestoneCompletion(ExecutionContext context) {
        UUID executionId = context.getExecutionId();
        int currentCompletedCount = context.getCompletedMilestones().size();
        Integer previousCount = previousCompletedMilestoneCounts.get(executionId);
        
        // Initialize previous count if not set
        if (previousCount == null) {
            previousCompletedMilestoneCounts.put(executionId, currentCompletedCount);
            return false;
        }
        
        // Check if milestone count increased (new milestone completed)
        boolean milestoneJustCompleted = currentCompletedCount > previousCount;
        
        // Update previous count
        previousCompletedMilestoneCounts.put(executionId, currentCompletedCount);
        
        // Also check if we reached final milestone
        String currentMilestone = context.getCurrentMilestone();
        boolean reachedFinal = currentMilestone != null && currentMilestone.equals("Complete");
        
        return milestoneJustCompleted || reachedFinal;
    }

    public int getActiveContextCount() {
        return activeContexts.size();
    }
    
    /**
     * Save context to database
     * Distinguishes between critical and non-critical persistence failures
     */
    private void saveContext(ExecutionContext context) {
        try {
            ExecutionContextEntity entity = ExecutionContextEntity.builder()
                    .executionId(context.getExecutionId())
                    .goal(context.getGoal())
                    .currentStep(context.getCurrentStep())
                    .currentMilestone(context.getCurrentMilestone())
                    .completedMilestones(objectMapper.writeValueAsString(context.getCompletedMilestones()))
                    .observations(objectMapper.writeValueAsString(context.getObservations()))
                    .toolResults(objectMapper.writeValueAsString(context.getToolResults()))
                    .artifacts(objectMapper.writeValueAsString(context.getArtifactReferences()))
                    .variables(objectMapper.writeValueAsString(context.getVariables()))
                    .knowledgeReferences(objectMapper.writeValueAsString(context.getKnowledgeReferences()))
                    .retryHistory(objectMapper.writeValueAsString(context.getRetryHistory()))
                    .metadata(objectMapper.writeValueAsString(context.getMetadata()))
                    .plan(context.getPlan() != null ? objectMapper.writeValueAsString(context.getPlan()) : null)
                    .reviews(objectMapper.writeValueAsString(context.getReviews() != null ? context.getReviews() : new ArrayList<>()))
                    .failures(objectMapper.writeValueAsString(context.getFailures() != null ? context.getFailures() : new ArrayList<>()))
                    .outputs(objectMapper.writeValueAsString(context.getOutputs() != null ? context.getOutputs() : new ArrayList<>()))
                    .createdAt(context.getCreatedAt())
                    .updatedAt(context.getUpdatedAt())
                    .status("ACTIVE")
                    .build();

            // Check if entity already exists
            Optional<ExecutionContextEntity> existing = repository.findByExecutionId(context.getExecutionId());
            if (existing.isPresent()) {
                entity.setId(existing.get().getId());
            }

            repository.save(entity);
            String correlationId = CorrelationIdUtil.getCorrelationId();
            log.debug("[{}] Saved execution context to database: {}", correlationId, context.getExecutionId());
        } catch (Exception e) {
            String correlationId = CorrelationIdUtil.getCorrelationId();
            // Mark context as persistence-degraded in metadata
            context.getMetadata().put("persistenceStatus", "DEGRADED");
            context.getMetadata().put("persistenceError", e.getMessage());
            
            // Log with appropriate severity based on whether this is a critical checkpoint
            if (isCriticalCheckpoint(context)) {
                log.error("[{}] CRITICAL PERSISTENCE FAILURE: Cannot save checkpoint for execution {}. This may cause data loss on crash.", 
                        correlationId, context.getExecutionId(), e);
            } else {
                log.warn("[{}] Non-critical persistence failure for execution {}. Will retry on next checkpoint. Error: {}", 
                        correlationId, context.getExecutionId(), e.getMessage());
            }
        }
    }
    
    /**
     * Determine if this save represents a critical checkpoint
     * Critical checkpoints occur at milestone completions or after tool executions
     */
    private boolean isCriticalCheckpoint(ExecutionContext context) {
        // Milestone completion is always critical
        if (context.getCompletedMilestones().size() > 0) {
            return true;
        }
        
        // After tool execution is critical (has tool results)
        if (context.getToolResults() != null && !context.getToolResults().isEmpty()) {
            return true;
        }
        
        // After artifact creation is critical
        if (context.getArtifactReferences() != null && !context.getArtifactReferences().isEmpty()) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Load context from database
     */
    private ExecutionContext loadContext(UUID executionId) {
        try {
            Optional<ExecutionContextEntity> entityOpt = repository.findByExecutionId(executionId);
            if (entityOpt.isEmpty()) {
                log.warn("Execution context not found in database: {}", executionId);
                return null;
            }
            
            ExecutionContextEntity entity = entityOpt.get();
            
            // Reconstruct ExecutionContext from entity
            ExecutionContext context = ExecutionContext.builder()
                    .executionId(entity.getExecutionId())
                    .goal(entity.getGoal())
                    .currentStep(entity.getCurrentStep())
                    .currentMilestone(entity.getCurrentMilestone())
                    .completedMilestones(objectMapper.readValue(entity.getCompletedMilestones(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)))
                    .observations(objectMapper.readValue(entity.getObservations(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                    com.enterprise.ai.agent.model.Observation.class)))
                    .toolResults(objectMapper.readValue(entity.getToolResults(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                    com.enterprise.ai.agent.model.ToolResult.class)))
                    .artifactReferences(objectMapper.readValue(entity.getArtifacts(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                    com.enterprise.ai.agent.model.ArtifactReference.class)))
                    .variables(objectMapper.readValue(entity.getVariables(),
                            objectMapper.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class)))
                    .knowledgeReferences(objectMapper.readValue(entity.getKnowledgeReferences(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)))
                    .retryHistory(objectMapper.readValue(entity.getRetryHistory(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class,
                                    ExecutionContext.RetryHistory.class)))
                    .metadata(objectMapper.readValue(entity.getMetadata(),
                            objectMapper.getTypeFactory().constructMapType(java.util.Map.class, String.class, Object.class)))
                    .plan(entity.getPlan() != null ? objectMapper.readValue(entity.getPlan(),
                            objectMapper.getTypeFactory().constructType(com.enterprise.ai.agent.model.AgentPlan.class)) : null)
                    .reviews(objectMapper.readValue(entity.getReviews(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)))
                    .failures(objectMapper.readValue(entity.getFailures(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)))
                    .outputs(objectMapper.readValue(entity.getOutputs(),
                            objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, String.class)))
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();
            
            // Add to active contexts
            activeContexts.put(executionId, context);
            
            log.info("Loaded execution context from database: {}", executionId);
            return context;
        } catch (Exception e) {
            String correlationId = CorrelationIdUtil.getCorrelationId();
            log.error("[{}] Failed to load execution context from database: {}", correlationId, executionId, e);
            return null;
        }
    }
    
    /**
     * Mark context as completed in database
     */
    private void markContextCompleted(UUID executionId) {
        try {
            Optional<ExecutionContextEntity> entityOpt = repository.findByExecutionId(executionId);
            if (entityOpt.isPresent()) {
                ExecutionContextEntity entity = entityOpt.get();
                entity.setStatus("COMPLETED");
                entity.setUpdatedAt(java.time.LocalDateTime.now());
                entity.setCompletedAt(java.time.LocalDateTime.now());
                repository.save(entity);
                String correlationId = CorrelationIdUtil.getCorrelationId();
                log.debug("[{}] Marked execution context as completed: {}", correlationId, executionId);
            }
        } catch (Exception e) {
            String correlationId = CorrelationIdUtil.getCorrelationId();
            log.error("[{}] Failed to mark execution context as completed: {}", correlationId, executionId, e);
        }
    }
    
    /**
     * P1: Mark context as failed in database
     */
    public void markContextFailed(UUID executionId, String failureReason) {
        try {
            Optional<ExecutionContextEntity> entityOpt = repository.findByExecutionId(executionId);
            if (entityOpt.isPresent()) {
                ExecutionContextEntity entity = entityOpt.get();
                entity.setStatus("FAILED");
                entity.setUpdatedAt(java.time.LocalDateTime.now());
                entity.setCompletedAt(java.time.LocalDateTime.now());
                repository.save(entity);
                String correlationId = CorrelationIdUtil.getCorrelationId();
                log.info("[{}] Marked execution context as failed: {} - Reason: {}", correlationId, executionId, failureReason);
            }
        } catch (Exception e) {
            String correlationId = CorrelationIdUtil.getCorrelationId();
            log.error("[{}] Failed to mark execution context as failed: {}", correlationId, executionId, e);
        }
    }
}
