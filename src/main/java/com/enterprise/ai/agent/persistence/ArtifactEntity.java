package com.enterprise.ai.agent.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * ArtifactEntity - JPA entity for persisting Artifact.
 * Stores generated deliverables for long-term access.
 */
@Entity
@Table(name = "artifacts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(nullable = false, unique = true)
    private UUID artifactId;
    
    @Builder.Default
    @Column(nullable = false)
    private int version = 1;
    
    @Column(name = "parent_artifact_id")
    private UUID parentArtifactId;
    
    @Column(nullable = false)
    private String type;
    
    @Column(nullable = false)
    private String name;
    
    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;
    
    @Column(nullable = false)
    private String mimeType;
    
    @Column(nullable = false)
    private String createdBy;
    
    @Column(nullable = false)
    private UUID relatedExecutionId;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
