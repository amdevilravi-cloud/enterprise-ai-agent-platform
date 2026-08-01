package com.enterprise.ai.agent.artifact;

import com.enterprise.ai.agent.model.Artifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * ArtifactManagerImpl - In-memory implementation of ArtifactManager.
 * In Phase 3, this will be replaced with a database-backed implementation.
 */
@Component
@Slf4j
public class ArtifactManagerImpl implements ArtifactManager {
    
    private final Map<UUID, Artifact> artifacts = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> executionArtifacts = new ConcurrentHashMap<>();
    
    @Override
    public Artifact createArtifact(String type, String name, String content, String mimeType, 
                                   String createdBy, UUID relatedExecutionId) {
        return createArtifact(type, name, content, mimeType, createdBy, relatedExecutionId, 1, null);
    }
    
    @Override
    public Artifact createArtifact(String type, String name, String content, String mimeType, 
                                   String createdBy, UUID relatedExecutionId, int version, UUID parentArtifactId) {
        UUID artifactId = UUID.randomUUID();
        Artifact artifact = Artifact.builder()
                .artifactId(artifactId)
                .version(version)
                .parentArtifactId(parentArtifactId)
                .type(type)
                .name(name)
                .content(content)
                .mimeType(mimeType)
                .createdBy(createdBy)
                .relatedExecutionId(relatedExecutionId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        
        artifacts.put(artifactId, artifact);
        
        // Track artifacts by execution
        executionArtifacts.computeIfAbsent(relatedExecutionId, k -> new ArrayList<>()).add(artifactId);
        
        String lineageInfo = parentArtifactId != null ? " (parent: " + parentArtifactId + ")" : "";
        log.info("Created artifact: id={}, version={}, type={}, name={}, executionId={}{}", 
                artifactId, version, type, name, relatedExecutionId, lineageInfo);
        
        return artifact;
    }
    
    @Override
    public Artifact getArtifact(UUID artifactId) {
        Artifact artifact = artifacts.get(artifactId);
        if (artifact == null) {
            log.warn("Artifact not found: {}", artifactId);
        }
        return artifact;
    }
    
    @Override
    public List<Artifact> listArtifacts(UUID executionId) {
        List<UUID> artifactIds = executionArtifacts.getOrDefault(executionId, Collections.emptyList());
        List<Artifact> result = new ArrayList<>();
        
        for (UUID artifactId : artifactIds) {
            Artifact artifact = artifacts.get(artifactId);
            if (artifact != null) {
                result.add(artifact);
            }
        }
        
        log.debug("Listed {} artifacts for execution: {}", result.size(), executionId);
        return result;
    }
    
    @Override
    public Artifact updateArtifact(UUID artifactId, String content) {
        Artifact existingArtifact = artifacts.get(artifactId);
        if (existingArtifact == null) {
            log.warn("Cannot update artifact - not found: {}", artifactId);
            return null;
        }
        
        // Create new version with parent lineage
        int newVersion = existingArtifact.getVersion() + 1;
        Artifact newArtifact = createArtifact(
                existingArtifact.getType(),
                existingArtifact.getName(),
                content,
                existingArtifact.getMimeType(),
                existingArtifact.getCreatedBy(),
                existingArtifact.getRelatedExecutionId(),
                newVersion,
                existingArtifact.getArtifactId()  // Parent is the current artifact
        );
        
        log.info("Updated artifact: old_id={}, new_id={}, new_version={}", 
                artifactId, newArtifact.getArtifactId(), newVersion);
        
        return newArtifact;
    }
    
    @Override
    public List<Artifact> getArtifactVersions(UUID artifactId) {
        List<Artifact> versions = new ArrayList<>();
        Artifact current = artifacts.get(artifactId);
        
        if (current != null) {
            versions.add(current);
            
            // Find parent and all ancestors
            UUID parentId = current.getParentArtifactId();
            while (parentId != null) {
                Artifact parent = artifacts.get(parentId);
                if (parent != null) {
                    versions.add(parent);
                    parentId = parent.getParentArtifactId();
                } else {
                    break;
                }
            }
        }
        
        // Sort by version number (ascending)
        versions.sort(Comparator.comparingInt(Artifact::getVersion));
        
        log.debug("Found {} versions for artifact: {}", versions.size(), artifactId);
        return versions;
    }
    
    @Override
    public Artifact rollbackArtifact(UUID artifactId, int targetVersion) {
        List<Artifact> versions = getArtifactVersions(artifactId);
        
        for (Artifact version : versions) {
            if (version.getVersion() == targetVersion) {
                // Create new version from the target version
                int newVersion = versions.get(versions.size() - 1).getVersion() + 1;
                return createArtifact(
                        version.getType(),
                        version.getName(),
                        version.getContent(),
                        version.getMimeType(),
                        "rollback",
                        version.getRelatedExecutionId(),
                        newVersion,  // New version number
                        artifactId  // Current artifact becomes parent
                );
            }
        }
        
        log.warn("Cannot rollback - version {} not found for artifact: {}", targetVersion, artifactId);
        return null;
    }
    
    @Override
    public void deleteArtifact(UUID artifactId) {
        Artifact artifact = artifacts.remove(artifactId);
        if (artifact != null) {
            // Remove from execution tracking
            List<UUID> executionArtifactIds = executionArtifacts.get(artifact.getRelatedExecutionId());
            if (executionArtifactIds != null) {
                executionArtifactIds.remove(artifactId);
            }
            log.info("Deleted artifact: id={}", artifactId);
        } else {
            log.warn("Cannot delete artifact - not found: {}", artifactId);
        }
    }
}
