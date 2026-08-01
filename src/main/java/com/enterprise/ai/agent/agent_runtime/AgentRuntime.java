package com.enterprise.ai.agent.agent_runtime;

import com.enterprise.ai.agent.agent.Agent;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.graph.ExecutionEdge;
import com.enterprise.ai.agent.graph.ExecutionGraph;
import com.enterprise.ai.agent.graph.ExecutionNode;
import com.enterprise.ai.agent.memory.KnowledgeMemory;
import com.enterprise.ai.agent.model.*;
import com.enterprise.ai.agent.planner.Planner;
import com.enterprise.ai.agent.review.ArtifactReviewer;
import com.enterprise.ai.agent.tools.Tool;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.workflow.WorkflowManager;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.UUID;

@Component
@Slf4j
public class AgentRuntime implements Agent {

    private final ExecutionContextManager contextManager;
    private final Planner planner;
    private final ToolRegistry toolRegistry;
    private final ArtifactManager artifactManager;
    private final KnowledgeMemory knowledgeMemory;
    private final WorkflowManager workflowManager;
    private final ArtifactReviewer artifactReviewer;

    private static final double CONFIDENCE_THRESHOLD = 0.4;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 1000; // Initial retry delay
    private static final double DUPLICATE_SIMILARITY_THRESHOLD = 0.8;

    public AgentRuntime(ExecutionContextManager contextManager, Planner planner, ToolRegistry toolRegistry, 
                       ArtifactManager artifactManager, KnowledgeMemory knowledgeMemory, 
                       WorkflowManager workflowManager, ArtifactReviewer artifactReviewer) {
        this.contextManager = contextManager;
        this.planner = planner;
        this.toolRegistry = toolRegistry;
        this.artifactManager = artifactManager;
        this.knowledgeMemory = knowledgeMemory;
        this.workflowManager = workflowManager;
        this.artifactReviewer = artifactReviewer;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        log.info("Starting agent execution for goal: {}", request.getGoal());

        LocalDateTime startedAt = LocalDateTime.now();
        ExecutionContext context = null;
        Execution execution = null;
        List<AgentAction> actionsTaken = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        
        // Create execution graph for visualization
        ExecutionGraph executionGraph = ExecutionGraph.builder()
                .graphId(UUID.randomUUID())
                .goal(request.getGoal())
                .nodes(new ArrayList<>())
                .edges(new ArrayList<>())
                .metadata(ExecutionGraph.GraphMetadata.builder()
                        .startTime(startedAt.toString())
                        .status("RUNNING")
                        .build())
                .build();

        try {
            // Create execution context
            context = contextManager.createContext(request.getGoal());
            
            // Determine workflow based on goal (runtime owns workflow)
            List<String> workflow = workflowManager.determineWorkflow(request.getGoal());
            context.setVariable("workflow", workflow);
            log.info("Determined workflow: {}", workflow);
            
            // Set initial milestone from workflow
            String initialMilestone = workflowManager.getNextMilestone(workflow, null);
            context.setCurrentMilestone(initialMilestone);
            log.info("Initial milestone: {}", initialMilestone);
            
            // Store execution graph in context metadata for access during execution
            context.setMetadata("executionGraph", executionGraph);
            
            // Add initial milestone to execution graph
            executionGraph.setExecutionId(context.getExecutionId());
            addMilestoneNode(initialMilestone, executionGraph, 0);
            
            // Set initial context from request
            if (request.getContext() != null) {
                context.setVariable("initialContext", request.getContext());
            }
            if (request.getConversationId() != null) {
                context.setVariable("conversationId", request.getConversationId());
            }
            if (request.getKnowledgeBaseId() != null) {
                context.setVariable("knowledgeBaseId", request.getKnowledgeBaseId());
            }

            // Create Execution object (runtime owns execution)
            ExecutionState state = ExecutionState.builder()
                    .pendingActions(new ArrayList<>())
                    .build();
            
            execution = Execution.builder()
                    .executionId(context.getExecutionId())
                    .goal(request.getGoal())
                    .state(state)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .executionGraph(executionGraph)
                    .build();

            // Execute planning loop (ReAct pattern)
            int maxIterations = 15; // Increased to accommodate full workflow
            int iteration = 0;
            String lastPlannerReasoning = null;
            String lastPlannerDecision = null;
            String lastPlannerNextStep = null;
            double lastPlannerConfidence = 1.0;

            while (iteration < maxIterations) {
                iteration++;
                log.info("Execution iteration {} for context: {}", iteration, context.getExecutionId());

                // Get planning result from planner (planner is stateless)
                PlanningResult planningResult = planner.createPlan(context);
                log.info("Planning result: currentState={}, nextState={}, milestone={}, nextStep={}, confidence={}, actions={}", 
                        planningResult.getCurrentState(), planningResult.getNextState(), planningResult.getMilestone(),
                        planningResult.getNextStep(), planningResult.getConfidence(), planningResult.getActions().size());

                // Store planner output for response
                lastPlannerReasoning = planningResult.getReasoning();
                lastPlannerDecision = planningResult.getCurrentState().name() + " -> " + planningResult.getNextState().name();
                lastPlannerNextStep = planningResult.getNextStep();
                lastPlannerConfidence = planningResult.getConfidence();
                
                // Update milestone in context if provided
                if (planningResult.getMilestone() != null && !planningResult.getMilestone().isEmpty()) {
                    context.setCurrentMilestone(planningResult.getMilestone());
                    log.info("Current milestone: {}", planningResult.getMilestone());
                }
                
                // Force progression if stuck in information gathering
                int knowledgeSearchCount = (int) state.getToolResults().stream()
                        .filter(tr -> tr.getToolName().equals("knowledge_search"))
                        .count();
                
                if (knowledgeSearchCount >= 3 && planningResult.getCurrentState() == AgentState.EXECUTE) {
                    log.warn("Forcing progression from EXECUTE to ANALYZE after {} knowledge searches", knowledgeSearchCount);
                    // Override planner decision to force progression
                    context.setVariable("force_analyze", true);
                    context.setVariable("analysis_trigger", "Max information gathering steps reached");
                }

                // Check confidence threshold
                if (planningResult.getConfidence() < CONFIDENCE_THRESHOLD) {
                    log.warn("Low confidence ({}) for execution: {}, asking user for clarification", 
                            planningResult.getConfidence(), context.getExecutionId());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls, 
                            planningResult.getReasoning(), planningResult, startedAt, executionGraph);
                }

                // Handle agent state
                AgentState effectiveState = planningResult.getCurrentState();
                
                // Force progression if needed
                if (context.getVariable("force_analyze") != null && (Boolean) context.getVariable("force_analyze")) {
                    effectiveState = AgentState.ANALYZE;
                    log.info("Forcing state transition to ANALYZE due to progression logic");
                    context.setVariable("force_analyze", false);
                }
                
                // Runtime owns milestone progression - check if current milestone is complete
                String currentMilestone = context.getCurrentMilestone();
                
                if (currentMilestone != null) {
                    int artifactsCreated = context.getArtifacts().size();
                    
                    boolean milestoneComplete = workflowManager.isMilestoneComplete(
                            currentMilestone, 
                            state.getCompletedActions().size(), 
                            knowledgeSearchCount, 
                            artifactsCreated
                    );
                    
                    // Additional validation: check required artifact types
                    if (milestoneComplete) {
                        boolean hasRequiredArtifacts = workflowManager.hasRequiredArtifactType(
                                currentMilestone, 
                                context.getArtifacts()
                        );
                        
                        if (!hasRequiredArtifacts) {
                            log.warn("Milestone '{}' has required artifact count but missing required artifact type. Waiting for proper artifact generation.", currentMilestone);
                            milestoneComplete = false;
                        }
                    }
                    
                    if (milestoneComplete) {
                        log.info("Milestone '{}' is complete. Advancing to next milestone.", currentMilestone);
                        
                        // Add to completed milestones
                        context.addCompletedMilestone(currentMilestone);
                        
                        // Get next milestone (workflow is already defined at method start)
                        String nextMilestone = workflowManager.getNextMilestone(workflow, currentMilestone);
                        
                        // Add milestone advancement to execution graph
                        Object graphObj = context.getMetadata().get("executionGraph");
                        if (graphObj instanceof ExecutionGraph) {
                            addMilestoneNode(nextMilestone, (ExecutionGraph) graphObj, context.getCurrentStep());
                        }
                        if (nextMilestone != null) {
                            context.setCurrentMilestone(nextMilestone);
                            log.info("Advanced to next milestone: {}", nextMilestone);
                            
                            // Set milestone completion criteria in context for planner
                            context.setVariable("milestoneCriteria", workflowManager.getMilestoneCriteria(nextMilestone));
                        } else {
                            log.info("Reached final milestone: {}", currentMilestone);
                            context.setCurrentMilestone("Complete");
                            
                            // Workflow complete - finish execution
                            execution.setCompletedAt(LocalDateTime.now());
                            return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                    "Workflow completed successfully", planningResult, startedAt, executionGraph);
                        }
                        
                        contextManager.updateContext(context);
                    }
                }
                
                switch (effectiveState) {
                    case FINISH:
                        log.info("Agent state: FINISH for execution: {}", context.getExecutionId());
                        execution.setCompletedAt(LocalDateTime.now());
                        return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                planningResult.getReasoning(), planningResult, startedAt, executionGraph);

                    case RESPOND:
                        log.info("Agent state: RESPOND for execution: {}", context.getExecutionId());
                        execution.setCompletedAt(LocalDateTime.now());
                        return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                planningResult.getReasoning(), planningResult, startedAt, executionGraph);

                    case PLAN:
                        log.info("Agent state: PLAN for execution: {}", context.getExecutionId());
                        // PLAN state means we should transition to the next state
                        // If nextState is EXECUTE, we need to actually execute the actions
                        if (planningResult.getNextState() == AgentState.EXECUTE && !planningResult.getActions().isEmpty()) {
                            log.info("Transitioning from PLAN to EXECUTE with {} actions", planningResult.getActions().size());
                            // Execute the actions
                            state.setPendingActions(planningResult.getActions());
                            execution.setUpdatedAt(LocalDateTime.now());

                            for (AgentAction action : planningResult.getActions()) {
                                AgentAction executedAction = executeAction(action, context, state, toolCalls);
                                actionsTaken.add(executedAction);
                                state.incrementStep();
                                execution.setUpdatedAt(LocalDateTime.now());
                                contextManager.updateContext(context);
                            }
                        } else {
                            // Continue loop to get new plan
                            continue;
                        }
                        break;

                    case EXECUTE:
                        log.info("Agent state: EXECUTE for execution: {}", context.getExecutionId());
                        // Update execution state with planning result
                        state.setPendingActions(planningResult.getActions());
                        execution.setUpdatedAt(LocalDateTime.now());

                        // Execute actions (runtime owns execution)
                        for (AgentAction action : planningResult.getActions()) {
                            AgentAction executedAction = executeAction(action, context, state, toolCalls);
                            actionsTaken.add(executedAction);
                            state.incrementStep();
                            execution.setUpdatedAt(LocalDateTime.now());
                            contextManager.updateContext(context);
                        }
                        break;

                    case OBSERVE:
                        log.info("Agent state: OBSERVE for execution: {}", context.getExecutionId());
                        // Process observations without executing tools
                        // The planner has already analyzed context in OBSERVE state
                        context.setVariable("lastObservation", planningResult.getReasoning());
                        contextManager.updateContext(context);
                        break;

                    case ANALYZE:
                        log.info("Agent state: ANALYZE for execution: {}", context.getExecutionId());
                        // Pure reasoning step - LLM analyzes without tool execution
                        // Store analysis in context
                        String analysisContent = planningResult.getReasoning();
                        if (context.getVariable("analysis_trigger") != null) {
                            analysisContent = "Forced analysis: " + context.getVariable("analysis_trigger") + ". " + analysisContent;
                        }
                        context.setVariable("lastAnalysis", analysisContent);
                        contextManager.updateContext(context);
                        break;

                    case GENERATE_ARTIFACT:
                        log.info("Agent state: GENERATE_ARTIFACT for execution: {}", context.getExecutionId());
                        // Execute planner's actions to generate artifacts
                        if (!planningResult.getActions().isEmpty()) {
                            log.info("Executing {} actions to generate artifacts", planningResult.getActions().size());
                            state.setPendingActions(planningResult.getActions());
                            execution.setUpdatedAt(LocalDateTime.now());

                            for (AgentAction action : planningResult.getActions()) {
                                AgentAction executedAction = executeAction(action, context, state, toolCalls);
                                actionsTaken.add(executedAction);
                                state.incrementStep();
                                execution.setUpdatedAt(LocalDateTime.now());
                                contextManager.updateContext(context);
                            }
                        } else {
                            // Fallback: create artifact from reasoning if no actions
                            log.info("No actions provided, creating artifact from reasoning");
                            String artifactContent = planningResult.getReasoning();
                            String artifactType = determineArtifactType(context, planningResult);
                            String artifactName = generateArtifactName(artifactType, context.getCurrentMilestone());
                            
                            Artifact artifact = artifactManager.createArtifact(
                                    artifactType,
                                    artifactName,
                                    artifactContent,
                                    "text/markdown",
                                    "agent_runtime",
                                    context.getExecutionId()
                            );
                            
                            context.addArtifact(artifact);
                            contextManager.updateContext(context);
                            log.info("Created artifact from reasoning: id={}, type={}, name={}", 
                                    artifact.getArtifactId(), artifactType, artifactName);
                            
                            // Add artifact node to execution graph
                            Object graphObj = context.getMetadata().get("executionGraph");
                            if (graphObj instanceof ExecutionGraph) {
                                addArtifactNode(artifactName, artifactType, "agent_runtime", (ExecutionGraph) graphObj, state.getCompletedActions().size());
                            }
                        }
                        break;

                    case REVIEW:
                        log.info("Agent state: REVIEW for execution: {}", context.getExecutionId());
                        // Review and improve artifacts using ArtifactReviewer
                        if (!context.getArtifacts().isEmpty()) {
                            Artifact latestArtifact = context.getArtifacts().get(context.getArtifacts().size() - 1);
                            log.info("Reviewing latest artifact: id={}, type={}, name={}", 
                                    latestArtifact.getArtifactId(), latestArtifact.getType(), latestArtifact.getName());
                            
                            // Use ArtifactReviewer to generate improved version
                            Artifact reviewedArtifact = artifactReviewer.review(latestArtifact, context);
                            context.addArtifact(reviewedArtifact);
                            contextManager.updateContext(context);
                            
                            log.info("Artifact review complete: original_id={}, reviewed_id={}, new_version={}", 
                                    latestArtifact.getArtifactId(), reviewedArtifact.getArtifactId(), reviewedArtifact.getVersion());
                        } else {
                            log.warn("No artifacts available for review in execution: {}", context.getExecutionId());
                        }
                        break;

                    default:
                        log.warn("Unknown agent state: {} for execution: {}", 
                                effectiveState, context.getExecutionId());
                        execution.setCompletedAt(LocalDateTime.now());
                        return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                planningResult.getReasoning(), planningResult, startedAt, executionGraph);
                }
            }

            // Max iterations reached
            log.warn("Max iterations reached for execution: {}", context.getExecutionId());
            execution.setCompletedAt(LocalDateTime.now());
            return buildSuccessResponse(context, actionsTaken, toolCalls, 
                    "Execution completed after max iterations", null, startedAt, executionGraph);

        } catch (Exception e) {
            log.error("Error during agent execution", e);
            if (context != null) {
                contextManager.discardContext(context.getExecutionId());
            }
            return buildErrorResponse(e.getMessage(), startedAt);
        } finally {
            // Clean up context
            if (context != null) {
                contextManager.discardContext(context.getExecutionId());
            }
        }
    }

    /**
     * Add milestone node to execution graph
     */
    private void addMilestoneNode(String milestone, ExecutionGraph graph, int order) {
        ExecutionNode node = ExecutionNode.builder()
                .nodeId(UUID.randomUUID())
                .nodeType(ExecutionNode.NodeType.MILESTONE)
                .name(milestone)
                .data(Map.of("status", "ACTIVE"))
                .executionId(graph.getExecutionId())
                .order(order)
                .build();
        graph.addNode(node);
        log.debug("Added milestone node to graph: {}", milestone);
    }
    
    /**
     * Add tool node and edge to execution graph
     */
    private void addToolNode(String toolName, String milestoneName, ExecutionGraph graph, int order) {
        // Find milestone node
        ExecutionNode milestoneNode = graph.getNodesByType(ExecutionNode.NodeType.MILESTONE).stream()
                .filter(n -> n.getName().equals(milestoneName))
                .findFirst()
                .orElse(null);
        
        if (milestoneNode != null) {
            ExecutionNode toolNode = ExecutionNode.builder()
                    .nodeId(UUID.randomUUID())
                    .nodeType(ExecutionNode.NodeType.TOOL)
                    .name(toolName)
                    .data(Map.of("status", "EXECUTED"))
                    .executionId(graph.getExecutionId())
                    .order(order)
                    .build();
            graph.addNode(toolNode);
            
            // Add edge: milestone -> tool
            ExecutionEdge edge = ExecutionEdge.builder()
                    .edgeId(UUID.randomUUID())
                    .sourceNodeId(milestoneNode.getNodeId())
                    .targetNodeId(toolNode.getNodeId())
                    .edgeType(ExecutionEdge.EdgeType.EXECUTES)
                    .executionId(graph.getExecutionId())
                    .order(order)
                    .build();
            graph.addEdge(edge);
            
            log.debug("Added tool node and edge to graph: {} -> {}", milestoneName, toolName);
        }
    }
    
    /**
     * Add artifact node and edge to execution graph
     */
    private void addArtifactNode(String artifactName, String artifactType, String toolName, ExecutionGraph graph, int order) {
        // Find tool node
        ExecutionNode toolNode = graph.getNodesByType(ExecutionNode.NodeType.TOOL).stream()
                .filter(n -> n.getName().equals(toolName))
                .findFirst()
                .orElse(null);
        
        if (toolNode != null) {
            ExecutionNode artifactNode = ExecutionNode.builder()
                    .nodeId(UUID.randomUUID())
                    .nodeType(ExecutionNode.NodeType.ARTIFACT)
                    .name(artifactName)
                    .data(Map.of("type", artifactType, "status", "CREATED"))
                    .executionId(graph.getExecutionId())
                    .order(order)
                    .build();
            graph.addNode(artifactNode);
            
            // Add edge: tool -> artifact
            ExecutionEdge edge = ExecutionEdge.builder()
                    .edgeId(UUID.randomUUID())
                    .sourceNodeId(toolNode.getNodeId())
                    .targetNodeId(artifactNode.getNodeId())
                    .edgeType(ExecutionEdge.EdgeType.PRODUCES)
                    .data(artifactType)
                    .executionId(graph.getExecutionId())
                    .order(order)
                    .build();
            graph.addEdge(edge);
            
            log.debug("Added artifact node and edge to graph: {} -> {}", toolName, artifactName);
        }
    }

    private AgentAction executeAction(AgentAction action, ExecutionContext context, ExecutionState state, List<ToolCall> toolCalls) {
        log.info("Executing action: {} with tool: {}", action.getActionId(), action.getToolName());

        // Check for duplicate actions before executing
        if (isDuplicateAction(action, state.getCompletedActions())) {
            log.warn("Skipping duplicate action: {} with tool: {} and purpose: {}", 
                    action.getActionId(), action.getToolName(), action.getPurpose());
            
            // Mark as skipped but return the action with modified description
            action.setDescription(action.getDescription() + " (SKIPPED - DUPLICATE)");
            return action;
        }

        // Add tool node to execution graph
        ExecutionGraph graph = null;
        if (context != null) {
            // Try to get the execution graph from the execution state
            // For now, we'll pass it through context metadata
            Object graphObj = context.getMetadata().get("executionGraph");
            if (graphObj instanceof ExecutionGraph) {
                graph = (ExecutionGraph) graphObj;
                addToolNode(action.getToolName(), context.getCurrentMilestone(), graph, state.getCompletedActions().size());
            }
        }

        // Runtime owns action status - create execution state for this action
        ActionStatus actionStatus = ActionStatus.RUNNING;
        LocalDateTime toolStartedAt = LocalDateTime.now();
        int attempt = 0;
        Exception lastException = null;

        while (attempt <= MAX_RETRIES) {
            attempt++;
            try {
                Tool tool = toolRegistry.get(action.getToolName());
                if (tool == null) {
                    throw new IllegalArgumentException("Tool not found: " + action.getToolName());
                }

                ToolRequest toolRequest = ToolRequest.builder()
                        .toolName(action.getToolName())
                        .parameters(action.getParameters())
                        .build();

                ToolResult result = tool.execute(toolRequest, context, artifactManager);
                LocalDateTime toolCompletedAt = LocalDateTime.now();
                long durationMs = java.time.Duration.between(toolStartedAt, toolCompletedAt).toMillis();
                
                // Add parameters to result for tracking
                result.setParameters(action.getParameters());
                
                // Create ToolCall record
                ToolCall toolCall = ToolCall.builder()
                        .callId(UUID.randomUUID())
                        .actionId(action.getActionId())
                        .toolName(action.getToolName())
                        .parameters(action.getParameters())
                        .result(result.getResult())
                        .success(result.isSuccess())
                        .errorMessage(result.getErrorMessage())
                        .startedAt(toolStartedAt)
                        .completedAt(toolCompletedAt)
                        .durationMs(durationMs)
                        .build();
                toolCalls.add(toolCall);
                
                // Update execution state with tool result
                state.addToolResult(result);
                state.addCompletedAction(action);

                // Create observation from tool result
                Observation observation = Observation.builder()
                        .observationId(UUID.randomUUID().toString())
                        .toolName(tool.name())
                        .content(result.getResult())
                        .timestamp(LocalDateTime.now())
                        .success(result.isSuccess())
                        .errorMessage(result.getErrorMessage())
                        .build();
                state.addObservation(observation);
                context.addObservation(observation);

                // Store knowledge from tool result
                if (result.isSuccess() && result.getResult() != null && !result.getResult().isEmpty()) {
                    try {
                        knowledgeMemory.storeKnowledge(
                                result.getResult(),
                                action.getToolName(),
                                action.getParameters().containsKey("query") ? 
                                        action.getParameters().get("query").toString() : "unknown",
                                "fact",
                                context.getExecutionId()
                        );
                        log.debug("Stored knowledge from tool result: {}", action.getToolName());
                    } catch (Exception ke) {
                        log.warn("Failed to store knowledge from tool result", ke);
                    }
                }

                actionStatus = ActionStatus.COMPLETED;

                log.info("Action {} completed successfully on attempt {}", action.getActionId(), attempt);
                return action;

            } catch (Exception e) {
                lastException = e;
                log.error("Error executing action: {} on attempt {}/{}", action.getActionId(), attempt, MAX_RETRIES, e);
                
                // Add retry history
                context.addRetry(ExecutionContext.RetryHistory.builder()
                        .stepId(action.getActionId().toString())
                        .attempt(attempt)
                        .reason(e.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
                
                // If we haven't exhausted retries, wait and try again
                if (attempt < MAX_RETRIES) {
                    try {
                        long delayMs = RETRY_DELAY_MS * (long) Math.pow(2, attempt - 1); // Exponential backoff
                        log.info("Retrying action {} in {} ms", action.getActionId(), delayMs);
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries exhausted
        actionStatus = ActionStatus.FAILED;
        LocalDateTime toolCompletedAt = LocalDateTime.now();
        long durationMs = java.time.Duration.between(toolStartedAt, toolCompletedAt).toMillis();
        state.incrementRetry();

        // Create ToolCall record for failed execution
        ToolCall toolCall = ToolCall.builder()
                .callId(UUID.randomUUID())
                .actionId(action.getActionId())
                .toolName(action.getToolName())
                .parameters(action.getParameters())
                .result("")
                .success(false)
                .errorMessage(lastException != null ? lastException.getMessage() : "Unknown error")
                .startedAt(toolStartedAt)
                .completedAt(toolCompletedAt)
                .durationMs(durationMs)
                .build();
        toolCalls.add(toolCall);

        // Add error observation
        Observation errorObservation = Observation.builder()
                .observationId(UUID.randomUUID().toString())
                .toolName(action.getToolName())
                .content("Failed to execute tool after " + MAX_RETRIES + " attempts: " + 
                        (lastException != null ? lastException.getMessage() : "Unknown error"))
                .timestamp(LocalDateTime.now())
                .success(false)
                .errorMessage(lastException != null ? lastException.getMessage() : "Unknown error")
                .build();
        state.addObservation(errorObservation);
        context.addObservation(errorObservation);

        return action;
    }

    private AgentResponse buildSuccessResponse(ExecutionContext context, List<AgentAction> actionsTaken, 
            List<ToolCall> toolCalls, String answer, PlanningResult planningResult, LocalDateTime startedAt, ExecutionGraph executionGraph) {
        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();

        // Update graph metadata
        if (executionGraph != null) {
            executionGraph.getMetadata().setEndTime(completedAt.toString());
            executionGraph.getMetadata().setDurationMs(durationMs);
            executionGraph.getMetadata().setTotalNodes(executionGraph.getNodes().size());
            executionGraph.getMetadata().setTotalEdges(executionGraph.getEdges().size());
            executionGraph.getMetadata().setStatus("COMPLETED");
        }

        return AgentResponse.builder()
                .executionId(context.getExecutionId())
                .goal(context.getGoal())
                .answer(answer != null ? answer : "Execution completed")
                .completed(true)
                .actionsTaken(convertToLegacyActions(actionsTaken))
                .toolCalls(toolCalls)
                .observations(context.getObservations())
                .artifacts(context.getArtifacts())
                .executionGraph(executionGraph)
                .status("completed")
                .plannerReasoning(planningResult != null ? planningResult.getReasoning() : null)
                .plannerDecision(planningResult != null ? planningResult.getCurrentState().name() : null)
                .plannerNextStep(planningResult != null ? planningResult.getNextStep() : null)
                .plannerConfidence(planningResult != null ? planningResult.getConfidence() : null)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationMs(durationMs)
                .build();
    }

    private AgentResponse buildErrorResponse(String errorMessage, LocalDateTime startedAt) {
        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();

        return AgentResponse.builder()
                .executionId(UUID.randomUUID())
                .goal(null)
                .answer(null)
                .completed(false)
                .actionsTaken(new ArrayList<>())
                .toolCalls(new ArrayList<>())
                .observations(new ArrayList<>())
                .artifacts(new ArrayList<>())
                .status("failed")
                .errorMessage(errorMessage)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationMs(durationMs)
                .build();
    }

    // Temporary conversion for compatibility with existing AgentResponse model
    private List<com.enterprise.ai.agent.model.Action> convertToLegacyActions(List<AgentAction> agentActions) {
        List<com.enterprise.ai.agent.model.Action> legacyActions = new ArrayList<>();
        for (AgentAction agentAction : agentActions) {
            com.enterprise.ai.agent.model.Action legacyAction = com.enterprise.ai.agent.model.Action.builder()
                    .actionId(agentAction.getActionId().toString())
                    .toolName(agentAction.getToolName())
                    .description(agentAction.getDescription())
                    .parameters(agentAction.getParameters())
                    .status("completed")
                    .build();
            legacyActions.add(legacyAction);
        }
        return legacyActions;
    }

    private String determineArtifactType(ExecutionContext context, PlanningResult planningResult) {
        // Determine artifact type based on milestone and context
        String milestone = context.getCurrentMilestone();
        if (milestone != null) {
            if (milestone.toLowerCase().contains("outline")) {
                return "outline";
            } else if (milestone.toLowerCase().contains("thesis") || milestone.toLowerCase().contains("document")) {
                return "document";
            } else if (milestone.toLowerCase().contains("summary")) {
                return "summary";
            }
        }
        return "document"; // Default
    }

    private String generateArtifactName(String type, String milestone) {
        String baseName = type;
        if (milestone != null && !milestone.isEmpty()) {
            baseName = milestone.replaceAll("\\s+", "_").toLowerCase();
        }
        return baseName + ".md";
    }

    /**
     * Check if an action is a duplicate of previously completed actions.
     * Uses purpose and tool name to detect similar actions.
     */
    private boolean isDuplicateAction(AgentAction newAction, List<AgentAction> completedActions) {
        if (completedActions == null || completedActions.isEmpty()) {
            return false;
        }

        // Check by purpose first (most specific)
        if (newAction.getPurpose() != null && !newAction.getPurpose().isEmpty()) {
            for (AgentAction completedAction : completedActions) {
                if (completedAction.getPurpose() != null && 
                    completedAction.getPurpose().equalsIgnoreCase(newAction.getPurpose()) &&
                    completedAction.getToolName().equals(newAction.getToolName())) {
                    log.debug("Duplicate action detected by purpose: {} with tool: {}", 
                            newAction.getPurpose(), newAction.getToolName());
                    return true;
                }
            }
        }

        // Check by parameter similarity (for knowledge_search)
        if (newAction.getToolName().equals("knowledge_search") && 
            newAction.getParameters() != null && 
            newAction.getParameters().containsKey("query")) {
            
            String newQuery = newAction.getParameters().get("query").toString().toLowerCase();
            
            for (AgentAction completedAction : completedActions) {
                if (completedAction.getToolName().equals("knowledge_search") &&
                    completedAction.getParameters() != null &&
                    completedAction.getParameters().containsKey("query")) {
                    
                    String completedQuery = completedAction.getParameters().get("query").toString().toLowerCase();
                    double similarity = calculateSimilarity(newQuery, completedQuery);
                    
                    if (similarity >= DUPLICATE_SIMILARITY_THRESHOLD) {
                        log.debug("Duplicate action detected by query similarity: {}% (new: {}, completed: {})", 
                                (int)(similarity * 100), newQuery, completedQuery);
                        return true;
                    }
                }
            }
        }

        return false;
    }

    /**
     * Calculate similarity between two strings using simple token overlap.
     * Returns a value between 0.0 and 1.0.
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;

        String[] tokens1 = s1.split("\\s+");
        String[] tokens2 = s2.split("\\s+");

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
        if (maxTokens == 0) return 0.0;

        return (double) overlap / maxTokens;
    }
}
