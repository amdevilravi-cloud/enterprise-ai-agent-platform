package com.enterprise.ai.agent.memory;

import com.enterprise.ai.agent.model.KnowledgeNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * KnowledgeMemoryImpl - In-memory implementation of KnowledgeMemory.
 * Uses simple keyword matching for search. Can be enhanced with semantic search.
 */
@Component
@Slf4j
public class KnowledgeMemoryImpl implements KnowledgeMemory {
    
    private final Map<UUID, KnowledgeNode> knowledgeNodes = new ConcurrentHashMap<>();
    private final Map<UUID, List<UUID>> executionKnowledge = new ConcurrentHashMap<>();
    
    @Override
    public KnowledgeNode storeKnowledge(String content, String source, String query, String type, 
                                        UUID relatedExecutionId) {
        UUID nodeId = UUID.randomUUID();
        KnowledgeNode node = KnowledgeNode.builder()
                .nodeId(nodeId)
                .content(content)
                .source(source)
                .query(query)
                .type(type)
                .relatedExecutionId(relatedExecutionId)
                .relevanceScore(1.0)
                .createdAt(LocalDateTime.now())
                .lastAccessedAt(LocalDateTime.now())
                .accessCount(0)
                .build();
        
        knowledgeNodes.put(nodeId, node);
        
        // Track knowledge by execution
        executionKnowledge.computeIfAbsent(relatedExecutionId, k -> new ArrayList<>()).add(nodeId);
        
        log.info("Stored knowledge node: id={}, source={}, type={}, executionId={}", 
                nodeId, source, type, relatedExecutionId);
        
        return node;
    }
    
    @Override
    public KnowledgeNode getKnowledge(UUID nodeId) {
        KnowledgeNode node = knowledgeNodes.get(nodeId);
        if (node != null) {
            node.setLastAccessedAt(LocalDateTime.now());
            node.setAccessCount(node.getAccessCount() + 1);
            log.debug("Retrieved knowledge node: {}", nodeId);
        } else {
            log.warn("Knowledge node not found: {}", nodeId);
        }
        return node;
    }
    
    @Override
    public List<KnowledgeNode> searchKnowledge(String query, int limit) {
        String lowerQuery = query.toLowerCase();
        
        // Split query into individual words for better matching
        String[] queryWords = lowerQuery.split("\\s+");
        
        List<KnowledgeNode> results = knowledgeNodes.values().stream()
                .filter(node -> {
                    // Word-based matching instead of exact string matching
                    String lowerContent = node.getContent().toLowerCase();
                    String lowerQueryOrig = node.getQuery() != null ? node.getQuery().toLowerCase() : "";
                    String lowerType = node.getType().toLowerCase();
                    
                    // Count matching words
                    int contentMatches = 0;
                    int queryMatches = 0;
                    int typeMatches = 0;
                    
                    for (String word : queryWords) {
                        if (word.length() > 2) { // Skip very short words
                            if (lowerContent.contains(word)) contentMatches++;
                            if (lowerQueryOrig.contains(word)) queryMatches++;
                            if (lowerType.contains(word)) typeMatches++;
                        }
                    }
                    
                    // Require at least some word matches
                    int totalMatches = contentMatches + queryMatches + typeMatches;
                    return totalMatches >= 1;
                })
                .sorted((a, b) -> {
                    // Sort by relevance score (word matches)
                    String lowerQueryA = query.toLowerCase();
                    String[] queryWordsA = lowerQueryA.split("\\s+");
                    int scoreA = calculateMatchScore(a, queryWordsA);
                    
                    String lowerQueryB = query.toLowerCase();
                    String[] queryWordsB = lowerQueryB.split("\\s+");
                    int scoreB = calculateMatchScore(b, queryWordsB);
                    
                    // Sort by match score, then recency, then access count
                    int scoreCompare = Integer.compare(scoreB, scoreA);
                    if (scoreCompare != 0) return scoreCompare;
                    
                    int dateCompare = b.getLastAccessedAt().compareTo(a.getLastAccessedAt());
                    if (dateCompare != 0) return dateCompare;
                    return Integer.compare(b.getAccessCount(), a.getAccessCount());
                })
                .limit(limit)
                .collect(Collectors.toList());
        
        log.debug("Search for '{}' returned {} results", query, results.size());
        return results;
    }
    
    /**
     * Calculate match score for a knowledge node against query words
     */
    private int calculateMatchScore(KnowledgeNode node, String[] queryWords) {
        String lowerContent = node.getContent().toLowerCase();
        String lowerQueryOrig = node.getQuery() != null ? node.getQuery().toLowerCase() : "";
        String lowerType = node.getType().toLowerCase();
        
        int score = 0;
        for (String word : queryWords) {
            if (word.length() > 2) {
                if (lowerContent.contains(word)) score += 2; // Content matches weighted higher
                if (lowerQueryOrig.contains(word)) score += 3; // Original query matches weighted highest
                if (lowerType.contains(word)) score += 1;
            }
        }
        return score;
    }
    
    @Override
    public List<KnowledgeNode> getKnowledgeForExecution(UUID executionId) {
        List<UUID> nodeIds = executionKnowledge.getOrDefault(executionId, Collections.emptyList());
        List<KnowledgeNode> result = new ArrayList<>();
        
        for (UUID nodeId : nodeIds) {
            KnowledgeNode node = knowledgeNodes.get(nodeId);
            if (node != null) {
                result.add(node);
            }
        }
        
        log.debug("Retrieved {} knowledge nodes for execution: {}", result.size(), executionId);
        return result;
    }
    
    @Override
    public void deleteKnowledge(UUID nodeId) {
        KnowledgeNode node = knowledgeNodes.remove(nodeId);
        if (node != null) {
            // Remove from execution tracking
            List<UUID> executionNodeIds = executionKnowledge.get(node.getRelatedExecutionId());
            if (executionNodeIds != null) {
                executionNodeIds.remove(nodeId);
            }
            log.info("Deleted knowledge node: {}", nodeId);
        } else {
            log.warn("Cannot delete knowledge node - not found: {}", nodeId);
        }
    }
}
