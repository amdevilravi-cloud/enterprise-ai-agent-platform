package com.enterprise.ai.agent.memory;

import com.enterprise.ai.agent.model.KnowledgeNode;

import java.util.List;
import java.util.UUID;

/**
 * KnowledgeMemory - Service for managing knowledge nodes.
 * Converts tool results into persistent knowledge for future reference.
 */
public interface KnowledgeMemory {
    
    /**
     * Store a knowledge node
     */
    KnowledgeNode storeKnowledge(String content, String source, String query, String type, 
                                 UUID relatedExecutionId);
    
    /**
     * Retrieve knowledge by ID
     */
    KnowledgeNode getKnowledge(UUID nodeId);
    
    /**
     * Search for relevant knowledge based on query
     */
    List<KnowledgeNode> searchKnowledge(String query, int limit);
    
    /**
     * Get all knowledge for a specific execution
     */
    List<KnowledgeNode> getKnowledgeForExecution(UUID executionId);
    
    /**
     * Delete knowledge node
     */
    void deleteKnowledge(UUID nodeId);
}
