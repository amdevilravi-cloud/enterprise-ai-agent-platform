package com.enterprise.ai.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MilestoneDefinition - Structured definition of a workflow milestone.
 * Defines the exit criteria, allowed tools, required artifacts, and transitions for a milestone.
 * This enables declarative milestone configuration instead of hardcoded logic.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MilestoneDefinition {
    
    private String id;                      // Unique identifier for the milestone
    private String name;                    // Display name
    private String description;             // Human-readable description
    
    private List<String> allowedTools;     // Tools that can be used in this milestone
    private String requiredArtifactKey;     // Artifact key required for completion
    private String requiredArtifactType;    // Artifact type required for completion
    private String parentArtifactKey;       // Parent artifact key if this milestone builds on another
    
    private CompletionPolicy completionPolicy;  // How to determine completion
    private String nextMilestone;           // Default next milestone
    
    /**
     * CompletionPolicy - Defines how milestone completion is determined
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompletionPolicy {
        private int minCompletedActions;     // Minimum actions required
        private int minKnowledgeSearches;    // Minimum knowledge searches required
        private int minArtifacts;            // Minimum artifacts required
        private boolean requiresArtifact;    // Whether artifact is strictly required
        private boolean requiresToolExecution; // Whether specific tool execution is required
        private String requiredTool;         // Specific tool that must be executed
        private boolean requiresReviewPass;  // Whether review must pass
    }
    
    /**
     * Check if a tool is allowed in this milestone
     */
    public boolean isToolAllowed(String toolName) {
        if (allowedTools == null || allowedTools.isEmpty()) {
            return true; // No restriction if not specified
        }
        return allowedTools.contains(toolName);
    }
    
    /**
     * Check if this milestone requires a specific artifact
     */
    public boolean requiresArtifact() {
        return requiredArtifactKey != null && !requiredArtifactKey.isEmpty();
    }
}
