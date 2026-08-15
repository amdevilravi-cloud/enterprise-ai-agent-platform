package com.enterprise.ai.agent.agent_runtime;

import com.enterprise.ai.agent.persistence.ExecutionContextEntity;
import com.enterprise.ai.agent.persistence.ExecutionContextRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

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
    private final ExecutionContextRepository repository;
    private final ObjectMapper objectMapper;
    
    @Value("${context.batch.save.threshold:5}")
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
        if (context != null) {
            context.discard();
            log.info("Discarded execution context: {}", executionId);
        }
        
        // Mark as completed in database
        markContextCompleted(executionId);
    }
    
    /**
     * Check if this update represents a milestone completion
     */
    private boolean isMilestoneCompletion(ExecutionContext context) {
        // Check if a milestone was just completed
        String currentMilestone = context.getCurrentMilestone();
        return currentMilestone != null && 
               (currentMilestone.equals("Complete") || 
                context.getCompletedMilestones().size() > 0);
    }

    public int getActiveContextCount() {
        return activeContexts.size();
    }
    
    /**
     * Save context to database
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
            log.debug("Saved execution context to database: {}", context.getExecutionId());
        } catch (Exception e) {
            log.error("Failed to save execution context to database: {}", context.getExecutionId(), e);
        }
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
                    .createdAt(entity.getCreatedAt())
                    .updatedAt(entity.getUpdatedAt())
                    .build();
            
            // Add to active contexts
            activeContexts.put(executionId, context);
            
            log.info("Loaded execution context from database: {}", executionId);
            return context;
        } catch (Exception e) {
            log.error("Failed to load execution context from database: {}", executionId, e);
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
                log.debug("Marked execution context as completed: {}", executionId);
            }
        } catch (Exception e) {
            log.error("Failed to mark execution context as completed: {}", executionId, e);
        }
    }
}
