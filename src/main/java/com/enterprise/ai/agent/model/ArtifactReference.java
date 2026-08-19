package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * ArtifactReference - Lightweight reference to an artifact created during execution.
 * ArtifactManager remains the.source of truth. ExecutionContext stores references, not full artifacts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArtifactReference {
    private String artifactKey;      // Logical identity: executionId + key (e.g., "exec123_analysis")
    private UUID artifactId;          // Physical ID in ArtifactManager
    private String name;              // Display name (e.g., "analyze_information.md")
    private String type;              // Artifact type (e.g., "document", "outline", "analysis")
    private int version;              // Version number
    private String uri;               // Optional URI for artifact location
    private ArtifactStatus status;    // Artifact lifecycle status
    private String milestone;          // Milestone during which this artifact was created
    private String parentArtifactKey;  // Parent artifact key for lineage tracking

    public enum ArtifactStatus {
        CREATED,
        COMPLETED,
        FAILED
    }
}
