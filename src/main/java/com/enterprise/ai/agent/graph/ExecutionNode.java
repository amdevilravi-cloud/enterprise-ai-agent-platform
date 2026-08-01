package com.enterprise.ai.agent.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * ExecutionNode - Represents a node in the execution graph.
 * Nodes can be milestones, tools, or artifacts.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionNode {
    
    /**
     * Unique identifier for this node
     */
    private UUID nodeId;
    
    /**
     * Node type: MILESTONE, TOOL, or ARTIFACT
     */
    private NodeType nodeType;
    
    /**
     * Node name (milestone name, tool name, or artifact name)
     */
    private String name;
    
    /**
     * Node data (status, duration, etc.)
     */
    private Map<String, Object> data;
    
    /**
     * Related execution ID
     */
    private UUID executionId;
    
    /**
     * Order of execution within the graph
     */
    private int order;
    
    public enum NodeType {
        MILESTONE,
        TOOL,
        ARTIFACT
    }
}
