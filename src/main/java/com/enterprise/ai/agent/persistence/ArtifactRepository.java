package com.enterprise.ai.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * ArtifactRepository - JPA repository for ArtifactEntity.
 * Provides database operations for artifact persistence.
 */
@Repository
public interface ArtifactRepository extends JpaRepository<ArtifactEntity, UUID> {
    
    /**
     * Find artifact by artifact ID
     */
    Optional<ArtifactEntity> findByArtifactId(UUID artifactId);
    
    /**
     * Find all artifacts for a specific execution
     */
    List<ArtifactEntity> findByRelatedExecutionId(UUID relatedExecutionId);
    
    /**
     * Delete artifact by artifact ID
     */
    void deleteByArtifactId(UUID artifactId);
    
    /**
     * Delete all artifacts for a specific execution
     */
    void deleteByRelatedExecutionId(UUID relatedExecutionId);
}
