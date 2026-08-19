package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.model.ArtifactReference;
import com.enterprise.ai.agent.model.ToolOutcome;
import com.enterprise.ai.agent.tools.ToolRegistry;
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
    
    private final ToolRegistry toolRegistry;
    
    public WorkflowManager(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }
    
    // Explicit milestone to artifact key mapping - replaces string-based matching
    private static final Map<String, String> REQUIRED_ARTIFACT_KEYS = Map.of(
        "Analyze Information", "analysis",
        "Analyze Findings", "analysis",
        "Analyze Data", "analysis",
        "Generate Outline", "outline",
        "Synthesize Research", "outline",
        "Generate Insights", "outline",
        "Write Document", "document",
        "Create Report", "document",
        "Review Document", "review"
    );
    
    // Milestone definitions - state machine with explicit exit conditions
    private static final Map<String, MilestoneDefinition> MILESTONE_DEFINITIONS = Map.of(
        "Collect Information", MilestoneDefinition.builder()
            .id("collect_information")
            .name("Collect Information")
            .description("Gather initial information through knowledge searches")
            .allowedTools(List.of("knowledge_search"))
            .completionPolicy(MilestoneDefinition.CompletionPolicy.builder()
                .minCompletedActions(0)
                .minKnowledgeSearches(1)
                .minArtifacts(0)
                .requiresArtifact(false)
                .requiresToolExecution(true)
                .requiredTool("knowledge_search")
                .build())
            .nextMilestone("Analyze Information")
            .build(),

        "Analyze Information", MilestoneDefinition.builder()
            .id("analyze_information")
            .name("Analyze Information")
            .description("Synthesize collected information into analysis")
            .allowedTools(List.of("document_generator"))
            .requiredArtifactKey("analysis")
            .requiredArtifactType("document")
            .completionPolicy(MilestoneDefinition.CompletionPolicy.builder()
                .minCompletedActions(1)
                .minKnowledgeSearches(0)
                .minArtifacts(1)
                .requiresArtifact(true)
                .requiresToolExecution(true)
                .requiredTool("document_generator")
                .build())
            .nextMilestone("Generate Outline")
            .build(),
            
        "Generate Outline", MilestoneDefinition.builder()
            .id("generate_outline")
            .name("Generate Outline")
            .description("Create structured outline from analysis")
            .allowedTools(List.of("outline_generator"))
            .requiredArtifactKey("outline")
            .requiredArtifactType("document")
            .completionPolicy(MilestoneDefinition.CompletionPolicy.builder()
                .minCompletedActions(1)
                .minArtifacts(1)
                .requiresArtifact(true)
                .requiresToolExecution(true)
                .requiredTool("outline_generator")
                .build())
            .nextMilestone("Write Document")
            .build(),

        "Write Document", MilestoneDefinition.builder()
            .id("write_document")
            .name("Write Document")
            .description("Generate complete document from outline")
            .allowedTools(List.of("document_generator"))
            .requiredArtifactKey("document")
            .requiredArtifactType("document")
            .parentArtifactKey("outline")
            .completionPolicy(MilestoneDefinition.CompletionPolicy.builder()
                .minCompletedActions(1)
                .minArtifacts(1)
                .requiresArtifact(true)
                .requiresToolExecution(true)
                .requiredTool("document_generator")
                .build())
            .nextMilestone("Review Document")
            .build(),
            
        "Review Document", MilestoneDefinition.builder()
            .id("review_document")
            .name("Review Document")
            .description("Review and validate the generated document")
            .allowedTools(List.of("document_reviewer"))
            .requiredArtifactKey("review")
            .requiredArtifactType("review")
            .parentArtifactKey("document")
            .completionPolicy(MilestoneDefinition.CompletionPolicy.builder()
                .minCompletedActions(1)
                .minArtifacts(1)
                .requiresArtifact(true)
                .requiresToolExecution(true)
                .requiredTool("document_reviewer")
                .build())
            .nextMilestone("Complete")
            .build(),
            
        "Complete", MilestoneDefinition.builder()
            .id("complete")
            .name("Complete")
            .description("Workflow completion")
            .allowedTools(List.of())
            .completionPolicy(MilestoneDefinition.CompletionPolicy.builder()
                .build())
            .build()
    );

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
        MILESTONE_TOOLS.put("Analyze Information", Arrays.asList("document_generator"));
        MILESTONE_TOOLS.put("Analyze Findings", Arrays.asList("document_generator"));
        MILESTONE_TOOLS.put("Analyze Data", Arrays.asList("document_generator"));
        
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
     * Check if the current milestone is complete based on ExecutionContext.
     * This method now delegates to isMilestoneCompleteUsingDefinition for consistency.
     * P0: For information gathering milestones, skip artifact requirement to allow progression with documented gaps.
     */
    public boolean isMilestoneComplete(ExecutionContext context, int completedActions) {
        return isMilestoneCompleteUsingDefinition(context, completedActions);
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
     * Check if the required artifact was created during the specific milestone.
     * This prevents false milestone completion when an artifact exists from a previous milestone.
     */
    public boolean hasArtifactForMilestone(String milestone, List<ArtifactReference> artifactReferences) {
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
        
        // Check if we have an artifact with the required key AND created during this milestone
        return artifactReferences.stream().anyMatch(ref -> 
            (requiredArtifactKey.equalsIgnoreCase(ref.getArtifactKey()) ||
             ref.getName().toLowerCase().contains(requiredArtifactKey.toLowerCase())) &&
            milestone.equals(ref.getMilestone()));
    }
    
    /**
     * Get the required artifact key for a milestone
     * Uses explicit mapping instead of string-based matching for determinism
     */
    private String getRequiredArtifactKey(String milestone) {
        if (milestone == null) {
            return null;
        }
        
        // Use explicit mapping for known milestones
        String artifactKey = REQUIRED_ARTIFACT_KEYS.get(milestone);
        if (artifactKey != null) {
            return artifactKey;
        }
        
        // Fallback for information gathering milestones
        if (milestone.contains("Collect") || milestone.contains("Gather")) {
            return null;
        }
        
        // Unknown milestone - return null
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
     * Check if a milestone transition is valid based on the workflow.
     * This replaces the hard-coded rollback prevention in ExecutionContext.
     * Returns true if the transition is allowed, false if it would be a rollback.
     */
    public boolean canTransition(String currentMilestone, String proposedMilestone, List<String> workflow) {
        if (currentMilestone == null || proposedMilestone == null) {
            return true; // Allow if either is null
        }
        
        if (currentMilestone.equals(proposedMilestone)) {
            return true; // Same milestone is allowed
        }
        
        if (workflow == null || workflow.isEmpty()) {
            // No workflow defined - use conservative approach: allow forward transitions only
            return !isLikelyRollback(currentMilestone, proposedMilestone);
        }
        
        int currentIndex = workflow.indexOf(currentMilestone);
        int proposedIndex = workflow.indexOf(proposedMilestone);
        
        if (currentIndex == -1 || proposedIndex == -1) {
            // Unknown milestone in workflow - use conservative approach
            return !isLikelyRollback(currentMilestone, proposedMilestone);
        }
        
        // Transition is valid if proposed index is greater than or equal to current index
        return proposedIndex >= currentIndex;
    }
    
    /**
     * Conservative fallback for unknown milestones.
     * Uses simple heuristics to detect likely rollbacks.
     */
    private boolean isLikelyRollback(String current, String proposed) {
        // If current is "Complete", any transition is a rollback
        if ("Complete".equals(current)) {
            return true;
        }
        
        // If proposed is "Complete", it's always allowed
        if ("Complete".equals(proposed)) {
            return false;
        }
        
        // Use string comparison as a weak heuristic
        return current.compareTo(proposed) > 0;
    }

    /**
     * Get recommended tools for a given milestone
     */
    public List<String> getToolsForMilestone(String milestone) {
        if (milestone == null) {
            return new ArrayList<>();
        }
        
        // Try exact match first
        if (MILESTONE_TOOLS.containsKey(milestone)) {
            return new ArrayList<>(MILESTONE_TOOLS.get(milestone));
        }
        
        // Try partial match for flexibility
        for (Map.Entry<String, List<String>> entry : MILESTONE_TOOLS.entrySet()) {
            if (milestone.contains(entry.getKey()) || entry.getKey().contains(milestone)) {
                return new ArrayList<>(entry.getValue());
            }
        }
        
        return new ArrayList<>();
    }

    /**
     * Get all available tool schemas
     * ToolRegistry is the single source of truth for tool schemas
     */
    public Map<String, ToolSchema> getAllToolSchemas() {
        return toolRegistry.getAllToolSchemas();
    }
    
    /**
     * Get milestone definition by name
     * Returns the structured definition with exit conditions and allowed tools
     */
    public MilestoneDefinition getMilestoneDefinition(String milestone) {
        if (milestone == null) {
            return null;
        }
        return MILESTONE_DEFINITIONS.get(milestone);
    }
    
    /**
     * Check milestone completion using milestone definition
     * This is the preferred method that uses structured definitions instead of hardcoded logic
     */
    public boolean isMilestoneCompleteUsingDefinition(ExecutionContext context, int completedActions) {
        String currentMilestone = context.getCurrentMilestone();
        if (currentMilestone == null || currentMilestone.isEmpty()) {
            return false;
        }
        
        MilestoneDefinition definition = MILESTONE_DEFINITIONS.get(currentMilestone);
        if (definition == null) {
            // Fallback to legacy method for unknown milestones
            return isMilestoneComplete(context, completedActions);
        }
        
        MilestoneDefinition.CompletionPolicy policy = definition.getCompletionPolicy();
        if (policy == null) {
            return true; // No policy means always complete
        }
        
        // Check minimum actions
        if (policy.getMinCompletedActions() > 0 && completedActions < policy.getMinCompletedActions()) {
            log.info("MILESTONE_EVALUATION milestone={} knowledgeSearchCalls={} requiredSearches={} completedActions={} requiredActions={} artifacts={} requiredArtifacts={} requiresArtifact={} result=false (insufficient actions)", 
                currentMilestone, 0, policy.getMinKnowledgeSearches(), 
                completedActions, policy.getMinCompletedActions(), 0, policy.getMinArtifacts(), 
                policy.isRequiresArtifact());
            return false;
        }
        
        // DIAGNOSTIC: Log all tool results before counting
        context.getToolResults().forEach(tr ->
            log.info("MILESTONE_TOOL_RESULT toolName={} success={} outcome={}", tr.getToolName(), tr.isSuccess(), tr.getOutcome())
        );

        // Check knowledge searches
        int knowledgeSearchCalls = (int) context.getToolResults().stream()
                .filter(tr -> tr.getToolName().equals("knowledge_search"))
                .filter(tr -> Boolean.TRUE.equals(tr.isSuccess()))
                .filter(tr -> tr.getOutcome() == null || tr.getOutcome() != ToolOutcome.SKIPPED_DUPLICATE)
                .count();
        if (policy.getMinKnowledgeSearches() > 0 && knowledgeSearchCalls < policy.getMinKnowledgeSearches()) {
            log.info("MILESTONE_EVALUATION milestone={} knowledgeSearchCalls={} requiredSearches={} completedActions={} requiredActions={} artifacts={} requiredArtifacts={} requiresArtifact={} result=false (insufficient searches)", 
                currentMilestone, knowledgeSearchCalls, policy.getMinKnowledgeSearches(), 
                completedActions, policy.getMinCompletedActions(), 0, policy.getMinArtifacts(), 
                policy.isRequiresArtifact());
            return false;
        }
        
        // Check minimum artifacts
        int artifactCount = context.getArtifactReferences().size();
        if (policy.getMinArtifacts() > 0 && artifactCount < policy.getMinArtifacts()) {
            log.info("MILESTONE_EVALUATION milestone={} knowledgeSearchCalls={} requiredSearches={} completedActions={} requiredActions={} artifacts={} requiredArtifacts={} requiresArtifact={} result=false (insufficient artifacts)", 
                currentMilestone, knowledgeSearchCalls, policy.getMinKnowledgeSearches(), 
                completedActions, policy.getMinCompletedActions(), artifactCount, policy.getMinArtifacts(), 
                policy.isRequiresArtifact());
            return false;
        }
        
        // Check if specific tool execution is required AND successful
        if (policy.isRequiresToolExecution() && policy.getRequiredTool() != null) {
            boolean toolSucceeded = context.getToolResults().stream()
                    .anyMatch(tr -> tr.getToolName().equals(policy.getRequiredTool())
                            && Boolean.TRUE.equals(tr.isSuccess()));
            if (!toolSucceeded) {
                log.info("MILESTONE_EVALUATION milestone={} knowledgeSearchCalls={} requiredSearches={} completedActions={} requiredActions={} artifacts={} requiredArtifacts={} requiresArtifact={} result=false (required tool not succeeded)",
                    currentMilestone, knowledgeSearchCalls, policy.getMinKnowledgeSearches(),
                    completedActions, policy.getMinCompletedActions(), artifactCount, policy.getMinArtifacts(),
                    policy.isRequiresArtifact());
                return false;
            }
        }
        
        // Check if artifact is required and exists for this milestone
        if (policy.isRequiresArtifact() && definition.requiresArtifact()) {
            if (!hasArtifactForMilestone(currentMilestone, context.getArtifactReferences())) {
                log.info("MILESTONE_EVALUATION milestone={} knowledgeSearchCalls={} requiredSearches={} completedActions={} requiredActions={} artifacts={} requiredArtifacts={} requiresArtifact={} hasRequiredArtifact=false result=false", 
                    currentMilestone, knowledgeSearchCalls, policy.getMinKnowledgeSearches(), 
                    completedActions, policy.getMinCompletedActions(), artifactCount, policy.getMinArtifacts(), 
                    policy.isRequiresArtifact());
                return false;
            }
        }
        
        log.info("MILESTONE_EVALUATION milestone={} knowledgeSearchCalls={} requiredSearches={} completedActions={} requiredActions={} artifacts={} requiredArtifacts={} requiresArtifact={} result=true", 
            currentMilestone, knowledgeSearchCalls, policy.getMinKnowledgeSearches(), 
            completedActions, policy.getMinCompletedActions(), artifactCount, policy.getMinArtifacts(), 
            policy.isRequiresArtifact());
        return true;
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
