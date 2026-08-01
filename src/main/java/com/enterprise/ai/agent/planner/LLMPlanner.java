package com.enterprise.ai.agent.planner;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.memory.KnowledgeMemory;
import com.enterprise.ai.agent.model.AgentAction;
import com.enterprise.ai.agent.model.AgentState;
import com.enterprise.ai.agent.model.KnowledgeNode;
import com.enterprise.ai.agent.model.PlanningResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
@Slf4j
public class LLMPlanner implements Planner {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final KnowledgeMemory knowledgeMemory;

    public LLMPlanner(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper, 
                      KnowledgeMemory knowledgeMemory) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.knowledgeMemory = knowledgeMemory;
    }

    @Override
    public PlanningResult createPlan(ExecutionContext context) {
        log.info("Creating plan for execution context: {} with goal: {}", context.getExecutionId(), context.getGoal());

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
        
        // Observations
        if (!context.getObservations().isEmpty()) {
            prompt.append("CURRENT OBSERVATIONS:\n");
            context.getObservations().forEach(obs -> {
                prompt.append("  - ").append(obs.getContent()).append("\n");
            });
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
        if (!context.getArtifacts().isEmpty()) {
            prompt.append("CREATED ARTIFACTS:\n");
            context.getArtifacts().forEach(artifact -> {
                prompt.append("  - [").append(artifact.getType()).append("] ").append(artifact.getName()).append("\n");
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
            
            return PlanningResult.builder()
                    .reasoning(reasoning)
                    .currentState(currentState)
                    .nextState(nextState)
                    .milestone(milestone)
                    .nextStep(nextStep)
                    .confidence(confidence)
                    .actions(actions)
                    .build();
                    
        } catch (Exception e) {
            log.error("Error parsing planning result from response: {}", response, e);
            return createFallbackResult(context);
        }
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
            paramsNode.fields().forEachRemaining(entry -> {
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
