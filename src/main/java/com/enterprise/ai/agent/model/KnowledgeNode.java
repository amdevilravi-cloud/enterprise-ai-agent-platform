package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * KnowledgeNode - Represents a unit of knowledge extracted from tool results.
 * Can be stored and retrieved for future reference to improve efficiency.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeNode {
    private UUID nodeId;
    private String content;
    private String source; // e.g., "knowledge_search", "web_search", "calculator"
    private String query; // The original query that generated this knowledge
    private String type; // e.g., "fact", "calculation", "reference"
    private UUID relatedExecutionId;
    private double relevanceScore;
    private LocalDateTime createdAt;
    private LocalDateTime lastAccessedAt;
    private int accessCount;
}
