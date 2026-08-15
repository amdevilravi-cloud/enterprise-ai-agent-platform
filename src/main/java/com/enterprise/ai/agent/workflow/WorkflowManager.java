package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.model.ArtifactReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WorkflowManager - Owns the workflow definition and milestone progression.
 * The runtime controls when to advance milestones, not the LLM planner.
 */
@Component
@Slf4j
public class WorkflowManager {

    // Pre-defined workflows for different task types
    
    // Milestone-to-tool mappings: defines which tools are appropriate for each milestone
    private static final Map<String, List<String>> MILESTONE_TOOLS = new HashMap<>();
    
    static {
        // Information gathering milestones
        MILESTONE_TOOLS.put("Collect Information", Arrays.asList("knowledge_search"));
        MILESTONE_TOOLS.put("Collect Overview", Arrays.asList("knowledge_search"));
        MILESTONE_TOOLS.put("Gather Detailed Information", Arrays.asList("knowledge_search"));
        MILESTONE_TOOLS.put("Collect Data", Arrays.asList("knowledge_search"));
        
        // Analysis milestones
        MILESTONE_TOOLS.put("Analyze Information", Arrays.asList("document_generator", "knowledge_search"));
        MILESTONE_TOOLS.put("Analyze Findings", Arrays.asList("document_generator", "knowledge_search"));
        MILESTONE_TOOLS.put("Analyze Data", Arrays.asList("document_generator", "knowledge_search"));
        
        // Outline/insight milestones
        MILESTONE_TOOLS.put("Generate Outline", Arrays.asList("document_generator"));
        MILESTONE_TOOLS.put("Synthesize Research", Arrays.asList("document_generator"));
        MILESTONE_TOOLS.put("Generate Insights", Arrays.asList("document_generator"));
        
        // Document creation milestones
        MILESTONE_TOOLS.put("Write Document", Arrays.asList("document_generator"));
        MILESTONE_TOOLS.put("Create Report", Arrays.asList("document_generator"));
        
        // Review milestones
        MILESTONE_TOOLS.put("Review Document", Arrays.asList("document_generator"));
    }
    
    // Tool schemas: defines input/output contracts for each tool
    private static final Map<String, ToolSchema> TOOL_SCHEMAS = new HashMap<>();
    
    static {
        TOOL_SCHEMAS.put("knowledge_search", ToolSchema.builder()
                .name("knowledge_search")
                .description("Search for information from knowledge base")
                .addParameter("query", "string", true)
                .produces("knowledge")
                .build());
        
        TOOL_SCHEMAS.put("document_generator", ToolSchema.builder()
                .name("document_generator")
                .description("Generate documents, analysis, outlines, or reports")
                .addParameter("content", "string", true)
                .addParameter("documentType", "string", false)
                .addParameter("instructions", "string", false)
                .produces("document")
                .build());
    }
    
    // Pre-defined workflows for different task types
    private static final List<String> DOCUMENT_CREATION_WORKFLOW = Arrays.asList(
            "Collect Information",
            "Analyze Information", 
            "Generate Outline",
            "Write Document",
            "Review Document",
            "Complete"
    );

    private static final List<String> RESEARCH_WORKFLOW = Arrays.asList(
            "Collect Overview",
            "Gather Detailed Information",
            "Analyze Findings",
            "Synthesize Research",
            "Complete"
    );

    private static final List<String> ANALYSIS_WORKFLOW = Arrays.asList(
            "Collect Data",
            "Analyze Data",
            "Generate Insights",
            "Create Report",
            "Complete"
    );

    /**
     * Determine the appropriate workflow based on the goal.
     */
    public List<String> determineWorkflow(String goal) {
        String goalLower = goal.toLowerCase();
        
        if (goalLower.contains("thesis") || goalLower.contains("document") || goalLower.contains("paper") || 
            goalLower.contains("write") || goalLower.contains("create document")) {
            log.info("Using DOCUMENT_CREATION_WORKFLOW for goal: {}", goal);
            return new ArrayList<>(DOCUMENT_CREATION_WORKFLOW);
        } else if (goalLower.contains("research") || goalLower.contains("investigate") || 
                   goalLower.contains("study")) {
            log.info("Using RESEARCH_WORKFLOW for goal: {}", goal);
            return new ArrayList<>(RESEARCH_WORKFLOW);
        } else if (goalLower.contains("analyze") || goalLower.contains("analysis") || 
                   goalLower.contains("report")) {
            log.info("Using ANALYSIS_WORKFLOW for goal: {}", goal);
            return new ArrayList<>(ANALYSIS_WORKFLOW);
        } else {
            // Default to document creation workflow
            log.info("Using default DOCUMENT_CREATION_WORKFLOW for goal: {}", goal);
            return new ArrayList<>(DOCUMENT_CREATION_WORKFLOW);
        }
    }

    /**
     * Get the next milestone in the workflow.
     */
    public String getNextMilestone(List<String> workflow, String currentMilestone) {
        if (workflow == null || workflow.isEmpty()) {
            return null;
        }

        if (currentMilestone == null || currentMilestone.isEmpty()) {
            return workflow.get(0); // Return first milestone
        }

        int currentIndex = workflow.indexOf(currentMilestone);
        if (currentIndex >= 0 && currentIndex < workflow.size() - 1) {
            return workflow.get(currentIndex + 1);
        }

        return null; // No next milestone (current is last)
    }

    /**
     * Check if the current milestone is complete based on execution context.
     * The runtime decides when a milestone is complete, not the LLM.
     */
    public boolean isMilestoneComplete(String milestone, int completedActions, 
                                          int knowledgeSearchCount, int artifactsCreated) {
        switch (milestone) {
            case "Collect Information":
            case "Collect Overview":
            case "Gather Detailed Information":
            case "Collect Data":
                // Information gathering milestones: need 1 successful knowledge search
                return knowledgeSearchCount >= 1;
                
            case "Analyze Information":
            case "Analyze Findings":
            case "Analyze Data":
                // Analysis milestones: need at least 1 successful action AND at least 1 knowledge search completed
                return completedActions >= 1 && knowledgeSearchCount >= 2;
                
            case "Generate Outline":
            case "Synthesize Research":
            case "Generate Insights":
                // Outline/insight milestones: MUST have at least 1 outline artifact
                // artifactsCreated alone is not enough - must verify artifact type
                return artifactsCreated >= 1; // Will be validated with type check in runtime
                
            case "Write Document":
            case "Create Report":
                // Document creation milestones: MUST have at least 1 document artifact
                return artifactsCreated >= 1; // Will be validated with type check in runtime
                
            case "Review Document":
                // Review milestones: need at least 1 successful action (review performed)
                return completedActions >= 1;
                
            case "Complete":
                // Final milestone: always considered complete
                return true;
                
            default:
                // Unknown milestone: require at least 1 action
                return completedActions >= 1;
        }
    }

    /**
     * Check if the current milestone is complete based on ExecutionContext.
     * This is the preferred method that uses artifact references for validation.
     */
    public boolean isMilestoneComplete(ExecutionContext context) {
        String currentMilestone = context.getCurrentMilestone();
        if (currentMilestone == null || currentMilestone.isEmpty()) {
            return false;
        }

        int completedActions = context.getToolResults().size();
        int knowledgeSearchCount = (int) context.getToolResults().stream()
                .filter(tr -> "knowledge_search".equals(tr.getToolName()))
                .count();
        int artifactCount = context.getArtifactReferences().size();

        return isMilestoneComplete(currentMilestone, completedActions, knowledgeSearchCount, artifactCount) &&
               hasRequiredArtifactType(currentMilestone, context.getArtifactReferences());
    }
    
    /**
     * Check if milestone has required artifact type
     * This should be called after isMilestoneComplete to validate artifact types
     * Updated to be more deterministic based on artifact keys
     */
    public boolean hasRequiredArtifactType(String milestone, List<ArtifactReference> artifactReferences) {
        if (artifactReferences == null || artifactReferences.isEmpty()) {
            // For information gathering milestones, no artifacts is acceptable
            return isInformationGatheringMilestone(milestone);
        }
        
        // Get required artifact key for this milestone
        String requiredArtifactKey = getRequiredArtifactKey(milestone);
        if (requiredArtifactKey == null) {
            // No specific artifact requirement
            return true;
        }
        
        // Check if we have an artifact with the required key
        return artifactReferences.stream().anyMatch(ref -> 
            requiredArtifactKey.equalsIgnoreCase(ref.getArtifactKey()) ||
            ref.getName().toLowerCase().contains(requiredArtifactKey.toLowerCase()));
    }
    
    /**
     * Get the required artifact key for a milestone
     */
    private String getRequiredArtifactKey(String milestone) {
        if (milestone == null) {
            return null;
        }
        
        // Map milestones to their required artifact keys
        if (milestone.contains("Collect") || milestone.contains("Gather")) {
            return null; // Information gathering doesn't require specific artifacts
        }
        if (milestone.contains("Analyze")) {
            return "analysis";
        }
        if (milestone.contains("Outline") || milestone.contains("Synthesize")) {
            return "outline";
        }
        if (milestone.contains("Write") || milestone.contains("Document") || milestone.contains("Report")) {
            return "document";
        }
        if (milestone.contains("Review")) {
            return "document"; // Review requires a document to review
        }
        
        return null;
    }
    
    /**
     * Check if a milestone is an information gathering milestone
     */
    public boolean isInformationGatheringMilestone(String milestone) {
        if (milestone == null) {
            return false;
        }
        
        return milestone.toLowerCase().contains("collect") || 
               milestone.toLowerCase().contains("gather") ||
               milestone.toLowerCase().contains("information");
    }

    /**
     * Get recommended tools for a given milestone
     */
    public List<String> getToolsForMilestone(String milestone) {
        if (milestone == null) {
            return new ArrayList<>();
        }
        
        // Try exact match first
        List<String> tools = MILESTONE_TOOLS.get(milestone);
        if (tools != null) {
            return tools;
        }
        
        // Try partial match
        for (Map.Entry<String, List<String>> entry : MILESTONE_TOOLS.entrySet()) {
            if (milestone.toLowerCase().contains(entry.getKey().toLowerCase()) ||
                entry.getKey().toLowerCase().contains(milestone.toLowerCase())) {
                return entry.getValue();
            }
        }
        
        return new ArrayList<>();
    }

    /**
     * Get tool schema for a specific tool
     */
    public ToolSchema getToolSchema(String toolName) {
        return TOOL_SCHEMAS.get(toolName);
    }

    /**
     * Get all available tool schemas
     */
    public Map<String, ToolSchema> getAllToolSchemas() {
        return new HashMap<>(TOOL_SCHEMAS);
    }

    /**
     * Get the completion criteria for a milestone.
     */
    public String getMilestoneCriteria(String milestone) {
        switch (milestone) {
            case "Collect Information":
            case "Collect Overview":
            case "Gather Detailed Information":
            case "Collect Data":
                return "Complete 1 knowledge search to gather relevant information";
                
            case "Analyze Information":
            case "Analyze Findings":
            case "Analyze Data":
                return "Analyze collected information and synthesize findings";
                
            case "Generate Outline":
            case "Synthesize Research":
            case "Generate Insights":
                return "Generate outline or insights based on analysis";
                
            case "Write Document":
            case "Create Report":
                return "Write the complete document or report";
                
            case "Review Document":
                return "Review and finalize the document";
                
            case "Complete":
                return "Execution complete";
                
            default:
                return "Complete the current milestone";
        }
    }

    /**
     * Check if this is the final milestone.
     */
    public boolean isFinalMilestone(String milestone, List<String> workflow) {
        if (workflow == null || workflow.isEmpty()) {
            return true;
        }
        return milestone != null && milestone.equals(workflow.get(workflow.size() - 1));
    }
}
