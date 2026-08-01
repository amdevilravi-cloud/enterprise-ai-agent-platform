package com.enterprise.ai.agent.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * ExecutionEdge - Represents a relationship between nodes in the execution graph.
 * Edges show the flow from milestones to tools to artifacts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionEdge {
    
    /**
     * Unique identifier for this edge
     */
    private UUID edgeId;
    
    /**
     * Source node ID (where the edge starts)
     */
    private UUID sourceNodeId;
    
    /**
     * Target node ID (where the edge ends)
     */
    private UUID targetNodeId;
    
    /**
     * Edge type: EXECUTES, PRODUCES, or FLOWS_TO
     */
    private EdgeType edgeType;
    
    /**
     * Edge data (tool parameters, artifact content, etc.)
     */
    private String data;
    
    /**
     * Related execution ID
     */
    private UUID executionId;
    
    /**
     * Order of execution for this edge
     */
    private int order;
    
    public enum EdgeType {
        EXECUTES,    // Milestone executes a tool
        PRODUCES,   // Tool produces an artifact
        FLOWS_TO    // One artifact flows to another (for tool chaining)
    }
}
