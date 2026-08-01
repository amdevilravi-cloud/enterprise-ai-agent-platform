package com.enterprise.ai.agent.graph;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * ExecutionGraph - Represents the complete execution flow as a graph.
 * Shows milestone → tool → artifact relationships.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionGraph {
    
    /**
     * Unique identifier for this graph
     */
    private UUID graphId;
    
    /**
     * Related execution ID
     */
    private UUID executionId;
    
    /**
     * Original goal that this execution was for
     */
    private String goal;
    
    /**
     * All nodes in the graph (milestones, tools, artifacts)
     */
    private List<ExecutionNode> nodes;
    
    /**
     * All edges in the graph (relationships between nodes)
     */
    private List<ExecutionEdge> edges;
    
    /**
     * Graph metadata (start time, end time, total duration, etc.)
     */
    private GraphMetadata metadata;
    
    /**
     * Add a node to the graph
     */
    public void addNode(ExecutionNode node) {
        if (this.nodes == null) {
            this.nodes = new ArrayList<>();
        }
        this.nodes.add(node);
    }
    
    /**
     * Add an edge to the graph
     */
    public void addEdge(ExecutionEdge edge) {
        if (this.edges == null) {
            this.edges = new ArrayList<>();
        }
        this.edges.add(edge);
    }
    
    /**
     * Get nodes by type
     */
    public List<ExecutionNode> getNodesByType(ExecutionNode.NodeType nodeType) {
        if (this.nodes == null) {
            return new ArrayList<>();
        }
        return this.nodes.stream()
                .filter(node -> node.getNodeType() == nodeType)
                .toList();
    }
    
    /**
     * Get edges by type
     */
    public List<ExecutionEdge> getEdgesByType(ExecutionEdge.EdgeType edgeType) {
        if (this.edges == null) {
            return new ArrayList<>();
        }
        return this.edges.stream()
                .filter(edge -> edge.getEdgeType() == edgeType)
                .toList();
    }
    
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GraphMetadata {
        private String startTime;
        private String endTime;
        private Long durationMs;
        private Integer totalNodes;
        private Integer totalEdges;
        private String status;
    }
}
