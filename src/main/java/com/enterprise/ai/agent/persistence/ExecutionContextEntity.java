package com.enterprise.ai.agent.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ExecutionContextEntity - JPA entity for persisting ExecutionContext.
 * Enables checkpointing and crash recovery for long-running tasks.
 */
@Entity
@Table(name = "execution_contexts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionContextEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false)
    private UUID executionId;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String goal;
    
    @Column(nullable = false)
    private int currentStep;
    
    @Column(columnDefinition = "TEXT")
    private String currentMilestone;
    
    @Column(columnDefinition = "TEXT")
    private String completedMilestones; // JSON array
    
    @Column(columnDefinition = "TEXT")
    private String observations; // JSON array
    
    @Column(columnDefinition = "TEXT")
    private String toolResults; // JSON array
    
    @Column(columnDefinition = "TEXT")
    private String artifacts; // JSON array
    
    @Column(columnDefinition = "TEXT")
    private String variables; // JSON object
    
    @Column(columnDefinition = "TEXT")
    private String knowledgeReferences; // JSON array
    
    @Column(columnDefinition = "TEXT")
    private String retryHistory; // JSON array
    
    @Column(columnDefinition = "TEXT")
    private String metadata; // JSON object
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    
    @Column
    private LocalDateTime completedAt;
    
    @Column(nullable = false)
    private String status; // "ACTIVE", "COMPLETED", "FAILED"
}
