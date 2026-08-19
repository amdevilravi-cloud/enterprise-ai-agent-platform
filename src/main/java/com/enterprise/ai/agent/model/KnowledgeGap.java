package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * KnowledgeGap - Represents a gap in knowledge discovered during execution.
 * Tracks what information was sought but not found, enabling smarter planning.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeGap {
    
    /**
     * The topic or subject that was searched for
     */
    private String topic;
    
    /**
     * Status of the knowledge gap
     */
    private GapStatus status;
    
    /**
     * Number of attempts made to find this information
     */
    @Builder.Default
    private int attempts = 0;
    
    /**
     * Queries that were attempted to find this information
     */
    @Builder.Default
    private List<String> queries = new ArrayList<>();
    
    /**
     * Sources that were checked for this information
     */
    @Builder.Default
    private List<String> sourcesChecked = new ArrayList<>();
    
    /**
     * When this gap was first identified
     */
    private LocalDateTime firstIdentifiedAt;
    
    /**
     * When this gap was last attempted
     */
    private LocalDateTime lastAttemptedAt;
    
    /**
     * Additional context about the gap
     */
    private String context;
    
    /**
     * Status of a knowledge gap
     */
    public enum GapStatus {
        /**
         * Information was not found in any checked sources
         */
        NOT_FOUND,
        
        /**
         * Partial information was found but insufficient
         */
        PARTIAL,
        
        /**
         * Information was successfully found
         */
        FOUND,
        
        /**
         * Gap is being actively searched
         */
        SEARCHING
    }
    
    /**
     * Record a new search attempt for this knowledge gap
     */
    public void recordAttempt(String query, String source) {
        this.attempts++;
        this.queries.add(query);
        if (source != null && !this.sourcesChecked.contains(source)) {
            this.sourcesChecked.add(source);
        }
        this.lastAttemptedAt = LocalDateTime.now();
    }
    
    /**
     * Mark this gap as found (information successfully retrieved)
     */
    public void markAsFound() {
        this.status = GapStatus.FOUND;
    }
    
    /**
     * Mark this gap as partial (some information found but insufficient)
     */
    public void markAsPartial() {
        this.status = GapStatus.PARTIAL;
    }
}
