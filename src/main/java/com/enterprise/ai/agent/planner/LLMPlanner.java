package com.enterprise.ai.agent.planner;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.memory.KnowledgeMemory;
import com.enterprise.ai.agent.model.AgentAction;
import com.enterprise.ai.agent.model.AgentPlan;
import com.enterprise.ai.agent.model.AgentState;
import com.enterprise.ai.agent.model.KnowledgeNode;
import com.enterprise.ai.agent.model.PlanningResult;
import com.enterprise.ai.agent.workflow.ToolSchema;
import com.enterprise.ai.agent.workflow.WorkflowManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Component
@Slf4j
public class LLMPlanner implements Planner {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeMemory knowledgeMemory;
    private final WorkflowManager workflowManager;
    
    @Value("${planner.stuck.iteration.threshold:3}")
    private int stuckIterationThreshold;
    
    private static final String ERR_SELF_TRANSITION = "STATE_TRANS_006";

    public LLMPlanner(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper, 
                      KnowledgeMemory knowledgeMemory, WorkflowManager workflowManager) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.knowledgeMemory = knowledgeMemory;
        this.workflowManager = workflowManager;
    }

    @Override
    public PlanningResult createPlan(ExecutionContext context) {
        log.info("Creating plan for execution context: {} with goal: {}", context.getExecutionId(), context.getGoal());

        // Check if we have recent skipped duplicate actions - if so, force a different approach
        if (hasRecentSkippedActions(context)) {
            log.warn("Recent skipped duplicate actions detected. Forcing planner to generate different approach.");
            return createDuplicateAvoidanceResult(context);
        }

        // Early termination for stuck states
        if (isStuckInMilestone(context)) {
            log.warn("Early termination: Context stuck in milestone '{}' for {} iterations. Forcing progression.", 
                    context.getCurrentMilestone(), context.getCurrentStep());
            return createStuckStateFallback(context);
        }

        // Pre-planning duplicate detection to avoid redundant LLM calls
        if (hasRecentSimilarPlan(context)) {
            log.info("Pre-planning duplicate detected. Skipping LLM call and returning cached-like result.");
            return createDuplicateAvoidanceResult(context);
        }

        String prompt = buildPlanningPrompt(context);

        try {
            String response = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            PlanningResult result = parsePlanningResult(response, context);
            log.info("Generated planning result: currentState={}, nextState={}, milestone={}, nextStep={}, confidence={}, actions={}", 
                    result.getCurrentState(), result.getNextState(), result.getMilestone(), result.getNextStep(), 
                    result.getConfidence(), result.getActions().size());
            
            return result;
        } catch (Exception e) {
            log.error("Error creating plan for execution: {}", context.getExecutionId(), e);
            return createFallbackResult(context);
        }
    }
    
    /**
     * Check if there are recent skipped duplicate actions
     */
    private boolean hasRecentSkippedActions(ExecutionContext context) {
        // Check observations for skip messages
        if (!context.getObservations().isEmpty()) {
            int size = context.getObservations().size();
            int skipCount = 0;
            int checkCount = Math.min(3, size);
            
            for (int i = 0; i < checkCount; i++) {
                var observation = context.getObservations().get(size - 1 - i);
                if (observation.getContent() != null && 
                    observation.getContent().contains("skipped as duplicate")) {
                    skipCount++;
                }
            }
            
            if (skipCount >= 2) {
                return true;
            }
        }
        
        // Also check if we have too many consecutive knowledge_search attempts
        // This catches the case where the same action keeps being planned
        if (context.getToolResults().size() >= 3) {
            long knowledgeSearchCount = context.getToolResults().stream()
                    .filter(tr -> tr.getToolName().equals("knowledge_search"))
                    .count();
            
            // If 80%+ of recent tool results are knowledge_search, we're stuck
            if (knowledgeSearchCount >= 3 && knowledgeSearchCount >= (context.getToolResults().size() * 0.8)) {
                log.warn("Detected {} consecutive knowledge_search attempts out of {}", 
                        knowledgeSearchCount, context.getToolResults().size());
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if context is stuck in the same milestone
     */
    private boolean isStuckInMilestone(ExecutionContext context) {
        return context.getCurrentStep() >= stuckIterationThreshold && 
               context.getToolResults().size() > 0 &&
               context.getArtifactReferences().isEmpty();
    }
    
    /**
     * Check if there's a recent similar plan to avoid redundant LLM calls
     */
    private boolean hasRecentSimilarPlan(ExecutionContext context) {
        // Check if we have recent skipped duplicate actions
        if (hasRecentSkippedActions(context)) {
            log.info("Detected recent skipped duplicate actions - should force different approach");
            return true; // This will trigger createDuplicateAvoidanceResult
        }
        
        // Check if we have recent tool results with similar queries
        if (context.getToolResults().size() < 2) {
            return false;
        }
        
        // Get last 2 tool results
        int size = context.getToolResults().size();
        var lastResult = context.getToolResults().get(size - 1);
        var secondLastResult = context.getToolResults().get(size - 2);
        
        // Check if both are knowledge_search with similar queries
        if (lastResult.getToolName().equals("knowledge_search") && 
            secondLastResult.getToolName().equals("knowledge_search")) {
            
            String lastQuery = extractQueryFromParams(lastResult.getParameters());
            String secondLastQuery = extractQueryFromParams(secondLastResult.getParameters());
            
            if (lastQuery != null && secondLastQuery != null) {
                double similarity = calculateQuerySimilarity(lastQuery, secondLastQuery);
                return similarity > 0.8; // 80% similarity threshold
            }
        }
        
        return false;
    }
    
    /**
     * Extract query from parameters map
     */
    private String extractQueryFromParams(java.util.Map<String, Object> params) {
        if (params != null && params.containsKey("query")) {
            return params.get("query").toString();
        }
        return null;
    }
    
    /**
     * Calculate similarity between two query strings
     */
    private double calculateQuerySimilarity(String query1, String query2) {
        if (query1 == null || query2 == null) return 0.0;
        if (query1.equalsIgnoreCase(query2)) return 1.0;
        
        String[] tokens1 = query1.toLowerCase().split("\\s+");
        String[] tokens2 = query2.toLowerCase().split("\\s+");
        
        int overlap = 0;
        for (String token1 : tokens1) {
            for (String token2 : tokens2) {
                if (token1.equals(token2)) {
                    overlap++;
                    break;
                }
            }
        }
        
        int maxTokens = Math.max(tokens1.length, tokens2.length);
        return maxTokens == 0 ? 0.0 : (double) overlap / maxTokens;
    }
    
    /**
     * Create a result to avoid duplicate planning
     */
    private PlanningResult createDuplicateAvoidanceResult(ExecutionContext context) {
        String currentMilestone = context.getCurrentMilestone();
        AgentState nextState = AgentState.ANALYZE;
        String nextStep = "Analyze collected information to avoid redundant searches";
        
        if (currentMilestone != null && currentMilestone.contains("Generate")) {
            nextState = AgentState.GENERATE_ARTIFACT;
            nextStep = "Generate artifact using collected information";
        }
        
        // Create a different action instead of returning empty list
        List<AgentAction> differentActions = new ArrayList<>();
        
        // If we're stuck on knowledge_search, try a different approach
        if (context.getToolResults().stream().anyMatch(tr -> tr.getToolName().equals("knowledge_search"))) {
            // Try to analyze what we have instead of searching more
            AgentAction analyzeAction = AgentAction.builder()
                    .actionId(UUID.randomUUID())
                    .toolName("calculator") // Use a different tool to break the loop
                    .purpose("Analyze collected information")
                    .parameters(Map.of("operation", "analyze"))
                    .build();
            differentActions.add(analyzeAction);
        }
        
        return PlanningResult.builder()
                .reasoning("Duplicate action detected. Switching to analysis phase to avoid redundant searches.")
                .currentState(AgentState.EXECUTE)
                .nextState(nextState)
                .milestone(currentMilestone)
                .nextStep(nextStep)
                .confidence(0.9)
                .actions(differentActions)
                .build();
    }
    
    /**
     * Create fallback result for stuck states
     */
    private PlanningResult createStuckStateFallback(ExecutionContext context) {
        String currentMilestone = context.getCurrentMilestone();
        AgentState fallbackState = AgentState.GENERATE_ARTIFACT;
        String fallbackStep = "Generate artifact to break deadlock";
        
        if (currentMilestone != null && currentMilestone.contains("Collect")) {
            fallbackState = AgentState.ANALYZE;
            fallbackStep = "Analyze collected information and proceed";
        }
        
        return PlanningResult.builder()
                .reasoning("Early termination: Stuck in milestone for " + context.getCurrentStep() + " iterations. Forcing progression.")
                .currentState(fallbackState)
                .nextState(fallbackState)
                .milestone(currentMilestone)
                .nextStep(fallbackStep)
                .confidence(0.7)
                .actions(new ArrayList<>())
                .build();
    }
    
    private String buildPlanningPrompt(ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an AI agent planner working within a workflow-driven architecture. ");
        prompt.append("The runtime controls milestone progression - you only decide actions within the current milestone.\n\n");
        
        prompt.append("=== EXECUTION STATE ===\n");
        prompt.append("GOAL: ").append(context.getGoal()).append("\n\n");
        
        // Current milestone and workflow progress
        prompt.append("CURRENT MILESTONE: ").append(context.getCurrentMilestone() != null ? context.getCurrentMilestone() : "Not set").append("\n");
        
        if (!context.getCompletedMilestones().isEmpty()) {
            prompt.append("COMPLETED MILESTONES:\n");
            context.getCompletedMilestones().forEach(milestone -> {
                prompt.append("  [✓] ").append(milestone).append("\n");
            });
            prompt.append("\n");
        }
        
        // Completed actions with purpose
        if (!context.getToolResults().isEmpty()) {
            prompt.append("COMPLETED ACTIONS:\n");
            context.getToolResults().forEach(result -> {
                prompt.append("  - ").append(result.getToolName());
                if (result.getParameters() != null && !result.getParameters().isEmpty()) {
                    prompt.append(" (").append(result.getParameters()).append(")");
                }
                prompt.append(" → ").append(result.isSuccess() ? "SUCCESS" : "FAILED");
                if (result.getDurationMs() > 0) {
                    prompt.append(" (").append(result.getDurationMs()).append("ms)");
                }
                prompt.append("\n");
            });
            prompt.append("\n");
        }
        
        // Observations - CRITICAL for detecting loops
        if (!context.getObservations().isEmpty()) {
            prompt.append("=== CRITICAL OBSERVATIONS ===\n");
            prompt.append("These observations show what happened with previous actions:\n");
            context.getObservations().forEach(obs -> {
                prompt.append("  - ").append(obs.getContent()).append("\n");
            });
            
            // Check for duplicate skip observations
            long skipCount = context.getObservations().stream()
                    .filter(obs -> obs.getContent() != null && obs.getContent().contains("skipped as duplicate"))
                    .count();
            
            if (skipCount > 0) {
                prompt.append("\n⚠️ CRITICAL: ").append(skipCount).append(" actions were skipped as duplicates. ");
                prompt.append("This means you are in a LOOP. ");
                prompt.append("You MUST choose a DIFFERENT tool or approach. ");
                prompt.append("DO NOT repeat the same action.\n\n");
            }
            prompt.append("\n");
        }
        
        // Variables and state
        if (!context.getVariables().isEmpty()) {
            prompt.append("CONTEXT VARIABLES:\n");
            context.getVariables().forEach((key, value) -> {
                prompt.append("  - ").append(key).append(": ").append(value).append("\n");
            });
            prompt.append("\n");
        }
        
        // Artifacts created
        if (!context.getArtifactReferences().isEmpty()) {
            prompt.append("CREATED ARTIFACTS:\n");
            context.getArtifactReferences().forEach(ref -> {
                prompt.append("  - [").append(ref.getType()).append("] ").append(ref.getName()).append("\n");
            });
            prompt.append("\n");
        }
        
        // Step counter
        prompt.append("CURRENT STEP: ").append(context.getCurrentStep()).append("\n\n");
        
        // Add iteration warning if stuck
        int knowledgeSearchCount = (int) context.getToolResults().stream()
                .filter(tr -> tr.getToolName().equals("knowledge_search"))
                .count();
        
        if (knowledgeSearchCount >= 3) {
            prompt.append("⚠️ CRITICAL WARNING: You have already performed ").append(knowledgeSearchCount).append(" knowledge searches. ");
            prompt.append("Information gathering phase is COMPLETE. ");
            prompt.append("You MUST transition to ANALYZE or GENERATE_ARTIFACT state. ");
            prompt.append("DO NOT perform more knowledge searches.\n\n");
        }

        // Add relevant knowledge from memory - search by execution context, not just goal
        String knowledgeSearchQuery = buildKnowledgeSearchQuery(context);
        List<KnowledgeNode> relevantKnowledge = knowledgeMemory.searchKnowledge(knowledgeSearchQuery, 5);
        if (!relevantKnowledge.isEmpty()) {
            prompt.append("=== RELEVANT KNOWLEDGE FROM MEMORY ===\n");
            relevantKnowledge.forEach(node -> {
                prompt.append("- [").append(node.getSource()).append("] ")
                      .append(node.getContent()).append("\n");
            });
            prompt.append("\n");
        }

        prompt.append("=== TOOL PARAMETERS ===\n");
        prompt.append("knowledge_search: Requires 'query' parameter (string)\n");
        prompt.append("outline_generator: Requires 'topic' parameter (string), optional 'content' and 'outlineType'\n");
        prompt.append("document_generator: Requires 'topic' parameter (string), optional 'content'\n");
        prompt.append("file_writer: Requires 'filename' and 'content' parameters\n");
        prompt.append("\n");

        prompt.append("=== AVAILABLE TOOLS ===\n");
        prompt.append("- knowledge_search: Search the knowledge base for information (parameters: query)\n");
        prompt.append("- calculator: Perform mathematical calculations\n");
        prompt.append("- document_generator: Generate documents using LLM (parameters: topic, content)\n");
        prompt.append("- outline_generator: Generate structured outlines (parameters: topic, content, outlineType)\n");
        prompt.append("- file_writer: Write content to filesystem (parameters: filename, content)\n");
        prompt.append("\n");

        // Add tool schemas from WorkflowManager
        prompt.append("=== TOOL SCHEMAS ===\n");
        Map<String, ToolSchema> toolSchemas = workflowManager.getAllToolSchemas();
        if (!toolSchemas.isEmpty()) {
            for (ToolSchema schema : toolSchemas.values()) {
                prompt.append("Tool: ").append(schema.getName()).append("\n");
                prompt.append("  Description: ").append(schema.getDescription()).append("\n");
                prompt.append("  Parameters:\n");
                if (schema.getParameters() != null && !schema.getParameters().isEmpty()) {
                    for (Map.Entry<String, ToolSchema.ParameterSchema> param : schema.getParameters().entrySet()) {
                        prompt.append("    - ").append(param.getKey()).append(": ")
                              .append(param.getValue().getType())
                              .append(param.getValue().isRequired() ? " (required)" : " (optional)")
                              .append("\n");
                    }
                }
                prompt.append("  Produces: ").append(schema.getProduces()).append("\n");
                prompt.append("\n");
            }
        }
        prompt.append("\n");

        // Add milestone-to-tool mappings
        prompt.append("=== MILESTONE TOOL MAPPINGS ===\n");
        String currentMilestone = context.getCurrentMilestone();
        if (currentMilestone != null) {
            List<String> recommendedTools = workflowManager.getToolsForMilestone(currentMilestone);
            if (!recommendedTools.isEmpty()) {
                prompt.append("Current milestone: ").append(currentMilestone).append("\n");
                prompt.append("Recommended tools: ").append(String.join(", ", recommendedTools)).append("\n");
                prompt.append("\n");
            }
        }
        prompt.append("\n");

        prompt.append("=== AVAILABLE STATES ===\n");
        prompt.append("- PLAN: Planning the next steps (initial state only)\n");
        prompt.append("- EXECUTE: Executing tools to gather information\n");
        prompt.append("- OBSERVE: Processing tool results\n");
        prompt.append("- ANALYZE: Analyzing information without tools (pure reasoning)\n");
        prompt.append("- GENERATE_ARTIFACT: Creating deliverables (documents, outlines, theses)\n");
        prompt.append("- REVIEW: Reviewing and improving artifacts\n");
        prompt.append("- RESPOND: Preparing to respond to user\n");
        prompt.append("- FINISH: Execution complete\n");
        prompt.append("\n");

        prompt.append("=== VALID STATE TRANSITIONS ===\n");
        prompt.append("You MUST follow these valid state transitions:\n");
        prompt.append("- PLAN → EXECUTE\n");
        prompt.append("- EXECUTE → OBSERVE\n");
        prompt.append("- OBSERVE → EXECUTE, ANALYZE\n");
        prompt.append("- ANALYZE → EXECUTE, GENERATE_ARTIFACT, RESPOND\n");
        prompt.append("- GENERATE_ARTIFACT → REVIEW, RESPOND, FINISH\n");
        prompt.append("- REVIEW → GENERATE_ARTIFACT, RESPOND, FINISH\n");
        prompt.append("- RESPOND → FINISH\n");
        prompt.append("- FINISH → (terminal state, no transitions)\n");
        prompt.append("\n");
        prompt.append("IMPORTANT: Never use invalid transitions like EXECUTE→REVIEW or FINISH→EXECUTE.\n");
        prompt.append("\n");

        prompt.append("=== WORKFLOW RULES ===\n");
        prompt.append("1. Milestone progression is controlled by the runtime, not by you.\n");
        prompt.append("2. You ONLY decide what actions to take within the current milestone.\n");
        prompt.append("3. DO NOT repeat actions that have already been completed successfully.\n");
        prompt.append("4. Maximum 3 knowledge searches total - after that, move to ANALYZE.\n");
        prompt.append("5. Use ANALYZE state to synthesize collected information.\n");
        prompt.append("6. Use GENERATE_ARTIFACT state to create deliverables.\n");
        prompt.append("7. Set currentState=EXECUTE when you want to run tools.\n");
        prompt.append("8. Be specific and purposeful in your actions.\n");
        prompt.append("\n");

        prompt.append("=== DECISION REQUIRED ===\n");
        prompt.append("Based on the current execution state above, determine:\n");
        prompt.append("1. What is the best action to take NOW?\n");
        prompt.append("2. Is the current milestone complete?\n");
        prompt.append("3. What should be the next step?\n");
        prompt.append("\n");

        prompt.append("Respond with a JSON object in the following format. Return ONLY the JSON, no other text:\n");
        prompt.append("{\n");
        prompt.append("  \"reasoning\": \"Your reasoning for the plan in plain text\",\n");
        prompt.append("  \"currentState\": \"PLAN|EXECUTE|OBSERVE|ANALYZE|GENERATE_ARTIFACT|RESPOND|FINISH\",\n");
        prompt.append("  \"nextState\": \"PLAN|EXECUTE|OBSERVE|ANALYZE|GENERATE_ARTIFACT|RESPOND|FINISH\",\n");
        prompt.append("  \"milestone\": \"Current milestone (keep same unless milestone is complete)\",\n");
        prompt.append("  \"milestoneComplete\": true/false,\n");
        prompt.append("  \"nextStep\": \"Specific next step description\",\n");
        prompt.append("  \"confidence\": 0.0 to 1.0,\n");
        prompt.append("  \"actions\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"toolName\": \"tool_name\",\n");
        prompt.append("      \"description\": \"What this action does\",\n");
        prompt.append("      \"purpose\": \"Purpose of this action (e.g., 'Collect overview', 'Analyze findings')\",\n");
        prompt.append("      \"parameters\": {\"param1\": \"value1\"}\n");
        prompt.append("    }\n");
        prompt.append("  ]\n");
        prompt.append("}\n");

        return prompt.toString();
    }

    private PlanningResult parsePlanningResult(String response, ExecutionContext context) {
        try {
            // Extract JSON from response (in case LLM adds extra text)
            String jsonContent = extractJson(response);
            
            JsonNode rootNode = objectMapper.readTree(jsonContent);
            
            // Parse reasoning
            String reasoning = rootNode.has("reasoning") ? rootNode.get("reasoning").asText() : "No reasoning provided";
            
            // Parse currentState
            AgentState currentState = AgentState.PLAN;
            if (rootNode.has("currentState")) {
                try {
                    currentState = AgentState.valueOf(rootNode.get("currentState").asText());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown currentState value: {}, defaulting to PLAN", rootNode.get("currentState").asText());
                }
            }
            
            // Parse nextState
            AgentState nextState = AgentState.PLAN;
            if (rootNode.has("nextState")) {
                try {
                    nextState = AgentState.valueOf(rootNode.get("nextState").asText());
                } catch (IllegalArgumentException e) {
                    log.warn("Unknown nextState value: {}, defaulting to PLAN", rootNode.get("nextState").asText());
                }
            }
            
            // Parse milestone
            String milestone = rootNode.has("milestone") ? rootNode.get("milestone").asText() : "";
            
            // Parse nextStep
            String nextStep = rootNode.has("nextStep") ? rootNode.get("nextStep").asText() : "Continue execution";
            
            // Parse confidence
            double confidence = rootNode.has("confidence") ? rootNode.get("confidence").asDouble() : 1.0;
            
            // Parse actions
            List<AgentAction> actions = new ArrayList<>();
            if (rootNode.has("actions") && rootNode.get("actions").isArray()) {
                for (JsonNode actionNode : rootNode.get("actions")) {
                    AgentAction action = AgentAction.builder()
                            .actionId(UUID.randomUUID())
                            .toolName(actionNode.has("toolName") ? actionNode.get("toolName").asText() : "unknown")
                            .description(actionNode.has("description") ? actionNode.get("description").asText() : "")
                            .purpose(actionNode.has("purpose") ? actionNode.get("purpose").asText() : "")
                            .parameters(extractParameters(actionNode))
                            .build();
                    actions.add(action);
                }
            }
            
            // Validate and correct state transitions
            PlanningResult result = validateAndCorrectTransitions(
                PlanningResult.builder()
                    .reasoning(reasoning)
                    .currentState(currentState)
                    .nextState(nextState)
                    .milestone(milestone)
                    .nextStep(nextStep)
                    .confidence(confidence)
                    .actions(actions)
                    .build(),
                context
            );
            
            return result;
                    
        } catch (Exception e) {
            log.error("Error parsing planning result from response: {}", response, e);
            return createFallbackResult(context);
        }
    }
    
    /**
     * Validate and correct state transitions to prevent infinite loops
     */
    private PlanningResult validateAndCorrectTransitions(PlanningResult result, ExecutionContext context) {
        AgentState currentState = result.getCurrentState();
        AgentState nextState = result.getNextState();
        String currentMilestone = context.getCurrentMilestone();
        
        // Use state transition validation matrix
        if (!isValidTransition(currentState, nextState)) {
            log.warn("[{}] Invalid state transition detected: {} -> {}. Correcting using validation matrix.", ERR_SELF_TRANSITION, currentState, nextState);
            nextState = getValidNextState(currentState, context);
            result = result.toBuilder().nextState(nextState).build();
        }
        
        // Prevent self-transitions (ANALYZE->ANALYZE, EXECUTE->EXECUTE, etc.)
        if (currentState == nextState) {
            log.warn("[{}] Self-transition detected: {} -> {}. Correcting to progress execution.", ERR_SELF_TRANSITION, currentState, nextState);
            
            // Determine appropriate next state based on current state and milestone
            if (currentState == AgentState.ANALYZE) {
                // After analysis, should generate artifact or move to next milestone
                if (isArtifactGenerationMilestone(currentMilestone)) {
                    result = result.toBuilder()
                        .nextState(AgentState.GENERATE_ARTIFACT)
                        .nextStep("Generate artifact based on analysis")
                        .build();
                } else {
                    result = result.toBuilder()
                        .nextState(AgentState.EXECUTE)
                        .nextStep("Proceed with next action")
                        .build();
                }
            } else if (currentState == AgentState.EXECUTE) {
                // After execution, should observe or analyze
                int knowledgeSearchCount = (int) context.getToolResults().stream()
                    .filter(tr -> tr.getToolName().equals("knowledge_search"))
                    .count();
                
                if (knowledgeSearchCount >= 3) {
                    result = result.toBuilder()
                        .nextState(AgentState.ANALYZE)
                        .nextStep("Analyze collected information")
                        .build();
                } else {
                    result = result.toBuilder()
                        .nextState(AgentState.OBSERVE)
                        .nextStep("Observe results")
                        .build();
                }
            } else {
                // For other self-transitions, default to EXECUTE
                result = result.toBuilder()
                    .nextState(AgentState.EXECUTE)
                    .nextStep("Continue execution")
                    .build();
            }
        }
        
        // Validate milestone transitions - planner should not change milestones
        if (result.getMilestone() != null && !result.getMilestone().isEmpty() 
            && !result.getMilestone().equals(currentMilestone)) {
            log.warn("Planner attempted milestone change: {} -> {}. Milestone progression is controlled by runtime. Reverting to current milestone: {}", 
                currentMilestone, result.getMilestone(), currentMilestone);
            result = result.toBuilder()
                .milestone(currentMilestone)
                .build();
        }
        
        // Prevent infinite EXECUTE->ANALYZE loops when stuck
        int knowledgeSearchCount = (int) context.getToolResults().stream()
            .filter(tr -> tr.getToolName().equals("knowledge_search"))
            .count();
        
        if (knowledgeSearchCount >= 3 && currentState == AgentState.EXECUTE && nextState == AgentState.ANALYZE) {
            // This pattern indicates the planner is stuck in a loop
            log.warn("Detected EXECUTE->ANALYZE loop with {} knowledge searches. Forcing artifact generation.", knowledgeSearchCount);
            result = result.toBuilder()
                .currentState(AgentState.GENERATE_ARTIFACT)
                .nextState(AgentState.GENERATE_ARTIFACT)
                .nextStep("Generate artifact to break execution loop")
                .build();
        }
        
        return result;
    }
    
    /**
     * Check if state transition is valid according to transition matrix
     */
    private boolean isValidTransition(AgentState from, AgentState to) {
        // Define valid transitions
        Map<AgentState, List<AgentState>> validTransitions = Map.of(
            AgentState.PLAN, List.of(AgentState.EXECUTE, AgentState.FINISH),
            AgentState.EXECUTE, List.of(AgentState.OBSERVE, AgentState.ANALYZE, AgentState.GENERATE_ARTIFACT, AgentState.RESPOND),
            AgentState.OBSERVE, List.of(AgentState.EXECUTE, AgentState.ANALYZE, AgentState.PLAN),
            AgentState.ANALYZE, List.of(AgentState.EXECUTE, AgentState.GENERATE_ARTIFACT, AgentState.RESPOND),
            AgentState.GENERATE_ARTIFACT, List.of(AgentState.REVIEW, AgentState.RESPOND, AgentState.FINISH),
            AgentState.REVIEW, List.of(AgentState.GENERATE_ARTIFACT, AgentState.RESPOND, AgentState.FINISH),
            AgentState.RESPOND, List.of(AgentState.FINISH),
            AgentState.FINISH, List.of() // Terminal state
        );
        
        List<AgentState> allowedNextStates = validTransitions.get(from);
        return allowedNextStates != null && allowedNextStates.contains(to);
    }
    
    /**
     * Get valid next state based on current state and context
     */
    private AgentState getValidNextState(AgentState currentState, ExecutionContext context) {
        // Default fallback transitions
        switch (currentState) {
            case PLAN:
                return AgentState.EXECUTE;
            case EXECUTE:
                return AgentState.OBSERVE;
            case OBSERVE:
                return AgentState.EXECUTE;
            case ANALYZE:
                return isArtifactGenerationMilestone(context.getCurrentMilestone()) 
                    ? AgentState.GENERATE_ARTIFACT 
                    : AgentState.EXECUTE;
            case GENERATE_ARTIFACT:
                return AgentState.RESPOND;
            case REVIEW:
                return AgentState.RESPOND;
            case RESPOND:
                return AgentState.FINISH;
            case FINISH:
                return AgentState.FINISH;
            default:
                return AgentState.EXECUTE;
        }
    }
    
    /**
     * Check if current milestone requires artifact generation
     */
    private boolean isArtifactGenerationMilestone(String milestone) {
        return milestone != null && (
            milestone.equals("Generate Outline") ||
            milestone.equals("Write Document") ||
            milestone.equals("Synthesize Research") ||
            milestone.equals("Generate Insights") ||
            milestone.equals("Create Report")
        );
    }

    private String extractJson(String response) {
        // Find the first { and last } to extract JSON
        int firstBrace = response.indexOf('{');
        int lastBrace = response.lastIndexOf('}');
        
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return response.substring(firstBrace, lastBrace + 1);
        }
        
        // If no braces found, return the whole response
        return response;
    }

    private java.util.Map<String, Object> extractParameters(JsonNode actionNode) {
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        if (actionNode.has("parameters") && actionNode.get("parameters").isObject()) {
            JsonNode paramsNode = actionNode.get("parameters");
            paramsNode.properties().forEach(entry -> {
                JsonNode valueNode = entry.getValue();
                if (valueNode.isTextual()) {
                    params.put(entry.getKey(), valueNode.asText());
                } else if (valueNode.isNumber()) {
                    params.put(entry.getKey(), valueNode.asDouble());
                } else if (valueNode.isBoolean()) {
                    params.put(entry.getKey(), valueNode.asBoolean());
                } else {
                    params.put(entry.getKey(), valueNode.toString());
                }
            });
        }
        return params;
    }

    /**
     * Build a knowledge search query based on execution context rather than just the goal.
     * This provides more targeted knowledge retrieval using semantic key terms.
     */
    private String buildKnowledgeSearchQuery(ExecutionContext context) {
        StringBuilder query = new StringBuilder();
        
        // Start with goal (most important for semantic meaning)
        query.append(context.getGoal());
        
        // Add current milestone for context
        if (context.getCurrentMilestone() != null) {
            query.append(" ").append(context.getCurrentMilestone());
        }
        
        // Extract semantic key terms from recent observations
        if (!context.getObservations().isEmpty()) {
            // Get the last 2 observations for context
            int limit = Math.min(2, context.getObservations().size());
            for (int i = context.getObservations().size() - limit; i < context.getObservations().size(); i++) {
                String obs = context.getObservations().get(i).getContent();
                if (obs != null && obs.length() > 0) {
                    // Extract key terms: first 50 chars + significant words
                    String keyTerms = extractKeyTerms(obs);
                    if (!keyTerms.isEmpty()) {
                        query.append(" ").append(keyTerms);
                    }
                }
            }
        }
        
        return query.toString();
    }
    
    /**
     * Extract key terms from observation text for semantic search
     */
    private String extractKeyTerms(String text) {
        // Take first 50 characters as primary context
        String primary = text.length() > 50 ? text.substring(0, 50) : text;
        
        // Extract significant words (words longer than 4 characters)
        String[] words = text.split("\\s+");
        StringBuilder significantWords = new StringBuilder();
        
        for (String word : words) {
            // Remove punctuation and check length
            String cleanWord = word.replaceAll("[^a-zA-Z0-9]", "");
            if (cleanWord.length() > 4) {
                significantWords.append(" ").append(cleanWord);
            }
            // Limit to avoid overly long queries
            if (significantWords.length() > 100) break;
        }
        
        return primary + significantWords.toString();
    }

    private PlanningResult createFallbackResult(ExecutionContext context) {
        log.warn("Creating fallback planning result for execution: {}", context.getExecutionId());
        return PlanningResult.builder()
                .reasoning("Unable to generate plan due to error. Providing direct response.")
                .currentState(AgentState.FINISH)
                .nextState(AgentState.FINISH)
                .milestone("Error recovery")
                .nextStep("FINISHED")
                .confidence(0.0)
                .actions(new ArrayList<>())
                .build();
    }
}
