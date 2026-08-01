package com.enterprise.ai.agent.graph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GraphVisualizationService - Formats execution graph data for UI visualization.
 * Provides graph filtering and export capabilities.
 */
@Component
@Slf4j
public class GraphVisualizationService {
    
    /**
     * Format graph data for UI consumption
     */
    public Map<String, Object> formatGraphForUI(ExecutionGraph graph) {
        Map<String, Object> formatted = new HashMap<>();
        
        formatted.put("graphId", graph.getGraphId());
        formatted.put("executionId", graph.getExecutionId());
        formatted.put("goal", graph.getGoal());
        formatted.put("metadata", graph.getMetadata());
        
        // Format nodes
        List<Map<String, Object>> formattedNodes = new ArrayList<>();
        for (ExecutionNode node : graph.getNodes()) {
            Map<String, Object> nodeData = new HashMap<>();
            nodeData.put("id", node.getNodeId());
            nodeData.put("type", node.getNodeType().name());
            nodeData.put("name", node.getName());
            nodeData.put("data", node.getData());
            nodeData.put("order", node.getOrder());
            formattedNodes.add(nodeData);
        }
        formatted.put("nodes", formattedNodes);
        
        // Format edges
        List<Map<String, Object>> formattedEdges = new ArrayList<>();
        for (ExecutionEdge edge : graph.getEdges()) {
            Map<String, Object> edgeData = new HashMap<>();
            edgeData.put("id", edge.getEdgeId());
            edgeData.put("source", edge.getSourceNodeId());
            edgeData.put("target", edge.getTargetNodeId());
            edgeData.put("type", edge.getEdgeType().name());
            edgeData.put("data", edge.getData());
            edgeData.put("order", edge.getOrder());
            formattedEdges.add(edgeData);
        }
        formatted.put("edges", formattedEdges);
        
        return formatted;
    }
    
    /**
     * Filter graph by node type
     */
    public ExecutionGraph filterByNodeType(ExecutionGraph graph, ExecutionNode.NodeType nodeType) {
        ExecutionGraph filtered = ExecutionGraph.builder()
                .graphId(graph.getGraphId())
                .executionId(graph.getExecutionId())
                .goal(graph.getGoal())
                .nodes(new ArrayList<>())
                .edges(new ArrayList<>())
                .metadata(graph.getMetadata())
                .build();
        
        // Add filtered nodes
        for (ExecutionNode node : graph.getNodes()) {
            if (node.getNodeType() == nodeType) {
                filtered.addNode(node);
            }
        }
        
        // Add edges that connect filtered nodes
        for (ExecutionEdge edge : graph.getEdges()) {
            boolean sourceInFiltered = filtered.getNodes().stream()
                    .anyMatch(n -> n.getNodeId().equals(edge.getSourceNodeId()));
            boolean targetInFiltered = filtered.getNodes().stream()
                    .anyMatch(n -> n.getNodeId().equals(edge.getTargetNodeId()));
            
            if (sourceInFiltered && targetInFiltered) {
                filtered.addEdge(edge);
            }
        }
        
        return filtered;
    }
    
    /**
     * Filter graph by milestone name
     */
    public ExecutionGraph filterByMilestone(ExecutionGraph graph, String milestoneName) {
        ExecutionGraph filtered = ExecutionGraph.builder()
                .graphId(graph.getGraphId())
                .executionId(graph.getExecutionId())
                .goal(graph.getGoal())
                .nodes(new ArrayList<>())
                .edges(new ArrayList<>())
                .metadata(graph.getMetadata())
                .build();
        
        // Find the milestone node
        ExecutionNode milestoneNode = graph.getNodesByType(ExecutionNode.NodeType.MILESTONE).stream()
                .filter(n -> n.getName().equals(milestoneName))
                .findFirst()
                .orElse(null);
        
        if (milestoneNode != null) {
            filtered.addNode(milestoneNode);
            
            // Add all nodes and edges that belong to this milestone's subtree
            addSubtree(graph, filtered, milestoneNode);
        }
        
        return filtered;
    }
    
    /**
     * Recursively add subtree nodes and edges
     */
    private void addSubtree(ExecutionGraph source, ExecutionGraph target, ExecutionNode node) {
        // Find outgoing edges from this node
        for (ExecutionEdge edge : source.getEdges()) {
            if (edge.getSourceNodeId().equals(node.getNodeId())) {
                // Find target node
                ExecutionNode targetNode = source.getNodes().stream()
                        .filter(n -> n.getNodeId().equals(edge.getTargetNodeId()))
                        .findFirst()
                        .orElse(null);
                
                if (targetNode != null && !target.getNodes().contains(targetNode)) {
                    target.addNode(targetNode);
                    target.addEdge(edge);
                    addSubtree(source, target, targetNode);
                }
            }
        }
    }
    
    /**
     * Export graph as JSON (already in JSON format)
     */
    public Map<String, Object> exportAsJSON(ExecutionGraph graph) {
        return formatGraphForUI(graph);
    }
    
    /**
     * Export graph as GraphML (simplified version)
     */
    public String exportAsGraphML(ExecutionGraph graph) {
        StringBuilder graphML = new StringBuilder();
        graphML.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        graphML.append("<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\"\n");
        graphML.append("  xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n");
        graphML.append("  xsi:schemaLocation=\"http://graphml.graphdrawing.org/xmlns\n");
        graphML.append("  http://graphml.graphdrawing.org/xmlns/1.0/graphml.xsd\">\n");
        graphML.append("  <graph id=\"").append(graph.getGraphId()).append("\" edgedefault=\"directed\">\n");
        
        // Add nodes
        for (ExecutionNode node : graph.getNodes()) {
            graphML.append("    <node id=\"").append(node.getNodeId()).append("\">\n");
            graphML.append("      <data key=\"type\">").append(node.getNodeType()).append("</data>\n");
            graphML.append("      <data key=\"name\">").append(node.getName()).append("</data>\n");
            graphML.append("    </node>\n");
        }
        
        // Add edges
        for (ExecutionEdge edge : graph.getEdges()) {
            graphML.append("    <edge source=\"").append(edge.getSourceNodeId())
                   .append("\" target=\"").append(edge.getTargetNodeId()).append("\">\n");
            graphML.append("      <data key=\"type\">").append(edge.getEdgeType()).append("</data>\n");
            graphML.append("    </edge>\n");
        }
        
        graphML.append("  </graph>\n");
        graphML.append("</graphml>");
        
        return graphML.toString();
    }
}
