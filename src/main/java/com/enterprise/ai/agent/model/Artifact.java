package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Artifact - Represents a generated deliverable (document, outline, thesis, etc.)
 * Generic model with type as string for flexibility.
 * Created by tools or LLM during execution.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Artifact {
    private UUID artifactId;
    @Builder.Default
    private int version = 1;  // Version number (starts at 1, increments on updates)
    private UUID parentArtifactId;  // Parent artifact ID for lineage tracking
    private String type;  // e.g., "document", "outline", "thesis", "summary", "diagram", "code"
    private String name;
    private String content;
    private String mimeType;  // e.g., "text/markdown", "text/plain", "application/json"
    private String createdBy;  // e.g., "document_generator", "outline_generator", "llm"
    private UUID relatedExecutionId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
