package com.enterprise.ai.agent.artifact;

import com.enterprise.ai.agent.model.Artifact;

import java.util.List;
import java.util.UUID;

/**
 * ArtifactManager - Service for managing artifacts during execution.
 * Handles creation, retrieval, and updates of artifacts.
 */
public interface ArtifactManager {
    
    /**
     * Create a new artifact
     */
    Artifact createArtifact(String type, String name, String content, String mimeType, 
                           String createdBy, UUID relatedExecutionId);
    
    /**
     * Create a new artifact with version information
     */
    Artifact createArtifact(String type, String name, String content, String mimeType, 
                           String createdBy, UUID relatedExecutionId, int version, UUID parentArtifactId);
    
    /**
     * Get artifact by ID
     */
    Artifact getArtifact(UUID artifactId);
    
    /**
     * List all artifacts for a specific execution
     */
    List<Artifact> listArtifacts(UUID executionId);
    
    /**
     * Update artifact content (creates new version)
     */
    Artifact updateArtifact(UUID artifactId, String content);
    
    /**
     * Get artifact version history
     */
    List<Artifact> getArtifactVersions(UUID artifactId);
    
    /**
     * Rollback artifact to previous version
     */
    Artifact rollbackArtifact(UUID artifactId, int targetVersion);
    
    /**
     * Delete artifact
     */
    void deleteArtifact(UUID artifactId);
}
