package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.model.Artifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * WorkflowManager - Owns the workflow definition and milestone progression.
 * The runtime controls when to advance milestones, not the LLM planner.
 */
@Component
@Slf4j
public class WorkflowManager {

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
                // Information gathering milestones: need 2-3 successful knowledge searches
                return knowledgeSearchCount >= 2;
                
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
     * Check if milestone has required artifact type
     * This should be called after isMilestoneComplete to validate artifact types
     */
    public boolean hasRequiredArtifactType(String milestone, List<Artifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) {
            return false;
        }
        
        switch (milestone) {
            case "Generate Outline":
            case "Synthesize Research":
            case "Generate Insights":
                // Must have at least 1 outline artifact
                return artifacts.stream().anyMatch(a -> "outline".equalsIgnoreCase(a.getType()));
                
            case "Write Document":
            case "Create Report":
                // Must have at least 1 document artifact
                return artifacts.stream().anyMatch(a -> "document".equalsIgnoreCase(a.getType()));
                
            default:
                // No specific artifact type requirement
                return true;
        }
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
                return "Complete 2-3 knowledge searches to gather relevant information";
                
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
