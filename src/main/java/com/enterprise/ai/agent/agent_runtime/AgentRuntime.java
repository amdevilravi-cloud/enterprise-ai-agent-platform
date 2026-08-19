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
import org.springframework.beans.factory.annotation.Value;
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

    @Value("${agent.confidence.threshold:0.4}")
    private double confidenceThreshold;
    
    @Value("${agent.max.retries:3}")
    private int maxRetries;
    
    @Value("${agent.retry.delay.ms:1000}")
    private long retryDelayMs;
    
    @Value("${agent.duplicate.similarity.threshold:0.8}")
    private double duplicateSimilarityThreshold;
    
    @Value("${agent.circuit.breaker.threshold:5}")
    private int circuitBreakerThreshold;
    
    @Value("${agent.stuck.iteration.threshold:8}")
    private int stuckIterationThreshold;
    
    @Value("${agent.max.knowledge.searches:3}")
    private int maxKnowledgeSearches;
    
    // Error codes for better debugging (these remain as constants)
    private static final String ERR_ARTIFACT_VALIDATION = "ARTIFACT_VAL_001";
    private static final String ERR_MILESTONE_STUCK = "MILESTONE_STUCK_002";
    private static final String ERR_CIRCUIT_BREAKER = "CIRCUIT_BREAK_003";
    private static final String ERR_PLANNING_FAILURE = "PLANNING_FAIL_004";
    private static final String ERR_GOAL_VALIDATION = "GOAL_VAL_005";
    private static final String ERR_SELF_TRANSITION = "STATE_TRANS_006";
    
    // Error classification for retry logic
    private enum ErrorClassification {
        RETRIABLE,      // Temporary errors that can be retried
        NON_RETRIABLE,  // Permanent errors that should not be retried
        RATE_LIMIT,     // Rate limiting errors
        TIMEOUT         // Timeout errors
    }

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
        String correlationId = UUID.randomUUID().toString();
        long startTime = System.currentTimeMillis();
        log.info("[{}] AGENT_EXECUTION_START: goal={}", correlationId, request.getGoal());

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
            context.setVariable("correlationId", correlationId);
            
            // Determine workflow based on goal (runtime owns workflow)
            List<String> workflow = workflowManager.determineWorkflow(request.getGoal());
            context.setVariable("workflow", workflow);
            log.info("[{}] Determined workflow: {}", correlationId, workflow);
            
            // Set initial milestone from workflow
            String initialMilestone = workflowManager.getNextMilestone(workflow, null);
            context.setCurrentMilestone(initialMilestone);
            log.info("[{}] Initial milestone: {}", correlationId, initialMilestone);
            
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
            // Initialize progress tracking in context
            context.setVariable("consecutiveFailures", 0);
            context.setVariable("lastProgressTimestamp", System.currentTimeMillis());
            String lastPlannerReasoning = null;
            String lastPlannerDecision = null;
            String lastPlannerNextStep = null;
            double lastPlannerConfidence = 1.0;

            while (iteration < maxIterations) {
                iteration++;
                log.info("[{}] Execution iteration {} for context: {}", correlationId, iteration, context.getExecutionId());

                // Check if workflow is complete - if so, stop planning and finish
                String currentMilestone = context.getCurrentMilestone();
                if ("Complete".equals(currentMilestone) || "FINISH".equals(currentMilestone)) {
                    // CRITICAL: Check for failed required actions before declaring success
                    if (hasFailedRequiredActions(context, workflowManager)) {
                        long executionTime = System.currentTimeMillis() - startTime;
                        log.warn("[{}] AGENT_EXECUTION_COMPLETE_WITH_FAILURES: goal={}, durationMs={}, actions={}",
                                correlationId, request.getGoal(), executionTime, actionsTaken.size());
                        execution.setCompletedAt(LocalDateTime.now());
                        execution.setStatus("COMPLETED_WITH_FAILURES");
                        return buildSuccessResponse(context, actionsTaken, toolCalls,
                                "Workflow completed with some failures. Check action results for details.", null, startedAt, executionGraph);
                    }
                    long executionTime = System.currentTimeMillis() - startTime;
                    log.info("[{}] AGENT_EXECUTION_COMPLETE: goal={}, durationMs={}, actions={}",
                            correlationId, request.getGoal(), executionTime, actionsTaken.size());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls,
                            "Workflow completed successfully", null, startedAt, executionGraph);
                }

                // Check for stuck execution (no progress for multiple iterations)
                Long lastProgressTimestamp = (Long) context.getVariable("lastProgressTimestamp");
                long timeSinceProgress = lastProgressTimestamp != null ? 
                    System.currentTimeMillis() - lastProgressTimestamp : 0;
                long stuckThresholdMs = stuckIterationThreshold * 1000; // Convert to milliseconds
                
                if (timeSinceProgress > stuckThresholdMs) {
                    log.warn("[{}] Execution stuck for {}ms without progress. Diagnostics only - forced progression disabled.", correlationId, timeSinceProgress);
                    // context.setVariable("force_progression", true);  // DISABLED
                    // context.setVariable("recovery_trigger", "Stuck execution recovery");  // DISABLED
                    context.setVariable("lastProgressTimestamp", System.currentTimeMillis()); // Reset to prevent immediate re-trigger
                }

                // Check circuit breaker for consecutive failures
                Integer consecutiveFailures = (Integer) context.getVariable("consecutiveFailures");
                if (consecutiveFailures != null && consecutiveFailures >= circuitBreakerThreshold) {
                    log.error("[{}] Circuit breaker triggered after {} consecutive failures. Aborting execution.", correlationId, consecutiveFailures);
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildErrorResponse("[" + ERR_CIRCUIT_BREAKER + "] Circuit breaker triggered: Too many consecutive failures", startedAt);
                }

                // Deterministic milestone completion check BEFORE planner call
                // This avoids unnecessary LLM invocations when milestone is already complete
                // Uses context-aware validation that includes strict artifact type checking
                if (currentMilestone != null && !"Complete".equals(currentMilestone)) {
                    boolean milestoneComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, state.getCompletedActions().size());
                    
                    if (milestoneComplete) {
                        log.info("[{}] Milestone '{}' is complete. Advancing to next milestone without planner call.", correlationId, currentMilestone);
                        
                        // Update progress tracking
                        context.setVariable("lastProgressTimestamp", System.currentTimeMillis());
                        
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
                            log.info("[{}] Advanced to next milestone: {}", correlationId, nextMilestone);
                            
                            // Set milestone completion criteria in context for planner
                            context.setVariable("milestoneCriteria", workflowManager.getMilestoneCriteria(nextMilestone));
                            
                            // Check if we reached Complete milestone
                            if ("Complete".equals(nextMilestone)) {
                                log.info("[{}] Reached final milestone: Complete. Finishing execution.", correlationId);
                                execution.setCompletedAt(LocalDateTime.now());
                                return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                        "Workflow completed successfully", null, startedAt, executionGraph);
                            }
                        } else {
                            log.info("[{}] No next milestone available. Finishing execution.", correlationId);
                            execution.setCompletedAt(LocalDateTime.now());
                            return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                    "Workflow completed successfully", null, startedAt, executionGraph);
                        }
                        
                        contextManager.updateContext(context);
                        continue; // Skip planner call and continue to next iteration
                    }
                }

                // Get planning result from planner (planner is stateless)
                PlanningResult planningResult;
                try {
                    planningResult = planner.createPlan(context);
                    context.setVariable("consecutiveFailures", 0); // Reset on successful planning
                } catch (Exception e) {
                    consecutiveFailures = (consecutiveFailures != null ? consecutiveFailures : 0) + 1;
                    context.setVariable("consecutiveFailures", consecutiveFailures);
                    log.error("[{}] Planning failed (attempt {}/{}): {}", ERR_PLANNING_FAILURE, consecutiveFailures, circuitBreakerThreshold, e.getMessage());
                    
                    if (consecutiveFailures >= circuitBreakerThreshold) {
                        log.error("[{}] Circuit breaker triggered. Aborting execution.", ERR_CIRCUIT_BREAKER);
                        execution.setCompletedAt(LocalDateTime.now());
                        return buildErrorResponse("[" + ERR_CIRCUIT_BREAKER + "] Circuit breaker triggered: Planning failures", startedAt);
                    }
                    
                    // Retry with fallback
                    planningResult = createFallbackPlanningResult(context);
                }
                
                log.info("[{}] Planning result: nextState={}, milestone={}, nextStep={}, confidence={}, actions={}", 
                        correlationId, planningResult.getNextState(), planningResult.getMilestone(),
                        planningResult.getNextStep(), planningResult.getConfidence(), planningResult.getActions().size());

                // Store planner output for response
                lastPlannerReasoning = planningResult.getReasoning();
                lastPlannerDecision = context.getCurrentState().name() + " -> " + planningResult.getNextState().name();
                lastPlannerNextStep = planningResult.getNextStep();
                lastPlannerConfidence = planningResult.getConfidence();
                
                // NOTE: Planner no longer controls milestone progression
                // Milestone progression is exclusively owned by WorkflowManager
                // Any milestone from planner is ignored
                
                // Force progression if stuck in information gathering (reduced threshold due to improved detection)
                int knowledgeSearchCount = (int) state.getToolResults().stream()
                        .filter(tr -> tr.getToolName().equals("knowledge_search"))
                        .count();
                
                // Check for skipped duplicate observations
                long skipCount = context.getObservations().stream()
                        .filter(obs -> obs.getContent() != null && obs.getContent().contains("skipped as duplicate"))
                        .count();
                
                // Force progression if we have 3+ knowledge searches OR 2+ skipped actions
                // TEMPORARILY DISABLED: Keep diagnostics but disable forced transitions to verify natural progression
                if ((knowledgeSearchCount >= 3 || skipCount >= 2) && context.getCurrentState() == AgentState.EXECUTE) {
                    log.warn("[{}] Loop detected: {} knowledge searches and {} skipped actions. Diagnostics only - forced progression disabled.", 
                            correlationId, knowledgeSearchCount, skipCount);
                    // context.setVariable("force_analyze", true);  // DISABLED
                    // context.setVariable("analysis_trigger", "Loop detected - forced milestone progression");  // DISABLED
                    // Mark current milestone as completed and advance
                    // if (currentMilestone != null && !currentMilestone.isEmpty()) {
                    //     context.addCompletedMilestone(currentMilestone);
                    //     context.setCurrentMilestone("ANALYZE");
                    //     log.info("[{}] Advanced milestone from '{}' to 'ANALYZE'", correlationId, currentMilestone);
                    // }
                }

                // Check confidence threshold
                if (planningResult.getConfidence() < confidenceThreshold) {
                    log.warn("[{}] Low confidence ({}) for execution: {}, asking user for clarification", 
                            correlationId, planningResult.getConfidence(), context.getExecutionId());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls, 
                            planningResult.getReasoning(), planningResult, startedAt, executionGraph);
                }

                // Handle agent state - use persistent state from context
                AgentState effectiveState = context.getCurrentState() != null ? context.getCurrentState() : AgentState.PLAN;
                
                // Force progression if needed (reduced usage due to improved detection)
                if (context.getVariable("force_analyze") != null && (Boolean) context.getVariable("force_analyze")) {
                    effectiveState = AgentState.ANALYZE;
                    log.info("[{}] Forcing state transition to ANALYZE due to progression logic", correlationId);
                    context.setVariable("force_analyze", false);
                }
                
                log.info("[{}] Planning result: currentState={}, nextState={}, nextStep={}, confidence={}, actions={}", 
                        correlationId, context.getCurrentState(), planningResult.getNextState(),
                        planningResult.getNextStep(), planningResult.getConfidence(), planningResult.getActions().size());

                // Store planner output for response
                lastPlannerReasoning = planningResult.getReasoning();
                lastPlannerDecision = context.getCurrentState().name() + " -> " + planningResult.getNextState().name();
                lastPlannerNextStep = planningResult.getNextStep();
                lastPlannerConfidence = planningResult.getConfidence();

                // NOTE: Planner no longer controls milestone progression
                // Milestone progression is now exclusively owned by WorkflowManager
                // based on deterministic artifact completion checks
                log.debug("[{}] Planner milestone suggestion ignored - using WorkflowManager for milestone control", correlationId);

                // Check confidence threshold
                if (planningResult.getConfidence() < confidenceThreshold) {
                    log.warn("[{}] Low confidence ({}) for execution: {}, asking user for clarification", 
                            correlationId, planningResult.getConfidence(), context.getExecutionId());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls, 
                            planningResult.getReasoning(), planningResult, startedAt, executionGraph);
                }

                // Force progression if needed (reduced usage due to improved detection)
                if (context.getVariable("force_analyze") != null && (Boolean) context.getVariable("force_analyze")) {
                    effectiveState = AgentState.ANALYZE;
                    log.info("[{}] Forcing state transition to ANALYZE due to progression logic", correlationId);
                    context.setVariable("force_analyze", false);
                }
                
                switch (effectiveState) {
                case FINISH:
                    log.info("[{}] Agent state: FINISH for execution: {}", correlationId, context.getExecutionId());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls, 
                            planningResult.getReasoning(), planningResult, startedAt, executionGraph);

                case RESPOND:
                    log.info("[{}] Agent state: RESPOND for execution: {}", correlationId, context.getExecutionId());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls, 
                            planningResult.getReasoning(), planningResult, startedAt, executionGraph);

                case PLAN:
                    log.info("[{}] Agent state: PLAN for execution: {}", correlationId, context.getExecutionId());
                    // PLAN state means we should transition to the next state
                    // If nextState is EXECUTE, we need to actually execute the actions
                    if (planningResult.getNextState() == AgentState.EXECUTE && !planningResult.getActions().isEmpty()) {
                        log.info("[{}] Transitioning from PLAN to EXECUTE with {} actions", correlationId, planningResult.getActions().size());
                        // Execute the actions
                        state.setPendingActions(planningResult.getActions());
                        execution.setUpdatedAt(LocalDateTime.now());

                        for (AgentAction action : planningResult.getActions()) {
                            AgentAction executedAction = executeAction(action, context, state, toolCalls, correlationId);
                            actionsTaken.add(executedAction);
                            state.incrementStep();
                            execution.setUpdatedAt(LocalDateTime.now());
                            contextManager.updateContext(context);
                        }
                        
                        // P0: Persist state transition after executing actions in PLAN state
                        if (planningResult.getNextState() != null) {
                            context.setCurrentState(planningResult.getNextState());
                            log.debug("[{}] State transition: PLAN -> {}", correlationId, planningResult.getNextState());
                        }
                        
                        // P0-1: Evaluate milestone completion AFTER tool execution
                        if (evaluateAndAdvanceMilestone(context, state, workflow, correlationId, executionGraph, startedAt, actionsTaken, toolCalls)) {
                            return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                    "Workflow completed successfully", null, startedAt, executionGraph);
                        }
                    } else {
                        // Continue loop to get new plan
                        continue;
                    }
                    break;

                case EXECUTE:
                    log.info("[{}] Agent state: EXECUTE for execution: {}", correlationId, context.getExecutionId());
                    // Update execution state with planning result
                    state.setPendingActions(planningResult.getActions());
                    execution.setUpdatedAt(LocalDateTime.now());

                    // Execute actions (runtime owns execution)
                    for (AgentAction action : planningResult.getActions()) {
                        AgentAction executedAction = executeAction(action, context, state, toolCalls, correlationId);
                        actionsTaken.add(executedAction);
                        state.incrementStep();
                        execution.setUpdatedAt(LocalDateTime.now());
                        contextManager.updateContext(context);
                    }
                    
                    // Persist nextState for next iteration
                    if (planningResult.getNextState() != null) {
                        context.setCurrentState(planningResult.getNextState());
                        log.debug("[{}] State transition: EXECUTE -> {}", correlationId, planningResult.getNextState());
                    }
                    
                    // P0-1: Evaluate milestone completion AFTER tool execution
                    // This is the key fix - check milestone before calling planner again
                    if (evaluateAndAdvanceMilestone(context, state, workflow, correlationId, executionGraph, startedAt, actionsTaken, toolCalls)) {
                        return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                "Workflow completed successfully", null, startedAt, executionGraph);
                    }
                    break;

                case OBSERVE:
                    log.info("[{}] Agent state: OBSERVE for execution: {}", correlationId, context.getExecutionId());
                    // Process observations without executing tools
                    // The planner has already analyzed context in OBSERVE state
                    context.setVariable("lastObservation", planningResult.getReasoning());
                    // Persist nextState for next iteration
                    if (planningResult.getNextState() != null) {
                        context.setCurrentState(planningResult.getNextState());
                        log.debug("[{}] State transition: OBSERVE -> {}", correlationId, planningResult.getNextState());
                    }
                    contextManager.updateContext(context);
                    break;

                case ANALYZE:
                    log.info("[{}] Agent state: ANALYZE for execution: {}", correlationId, context.getExecutionId());
                    // Pure reasoning step - LLM analyzes without tool execution
                    // Store analysis in context
                    String analysisContent = planningResult.getReasoning();
                    if (context.getVariable("analysis_trigger") != null) {
                        analysisContent = "Forced analysis: " + context.getVariable("analysis_trigger") + ". " + analysisContent;
                    }
                    context.setVariable("lastAnalysis", analysisContent);
                    // Persist nextState for next iteration
                    if (planningResult.getNextState() != null) {
                        context.setCurrentState(planningResult.getNextState());
                        log.debug("[{}] State transition: ANALYZE -> {}", correlationId, planningResult.getNextState());
                    }
                    contextManager.updateContext(context);
                    break;

                case GENERATE_ARTIFACT:
                    log.info("[{}] Agent state: GENERATE_ARTIFACT for execution: {}", correlationId, context.getExecutionId());
                    // Execute planner's actions to generate artifacts
                    if (!planningResult.getActions().isEmpty()) {
                        log.info("[{}] Executing {} actions to generate artifacts", correlationId, planningResult.getActions().size());
                        state.setPendingActions(planningResult.getActions());
                        execution.setUpdatedAt(LocalDateTime.now());

                        for (AgentAction action : planningResult.getActions()) {
                            AgentAction executedAction = executeAction(action, context, state, toolCalls, correlationId);
                            actionsTaken.add(executedAction);
                            state.incrementStep();
                            execution.setUpdatedAt(LocalDateTime.now());
                            contextManager.updateContext(context);
                        }
                        
                        // P0-1: Evaluate milestone completion AFTER tool execution
                        if (evaluateAndAdvanceMilestone(context, state, workflow, correlationId, executionGraph, startedAt, actionsTaken, toolCalls)) {
                            return buildSuccessResponse(context, actionsTaken, toolCalls, 
                                    "Workflow completed successfully", null, startedAt, executionGraph);
                        }
                    } else {
                        // P0: Do not create fake artifacts from reasoning
                        // Planner must explicitly request artifact generation tools
                        log.warn("[{}] GENERATE_ARTIFACT requested but no artifact action was provided. Planner must use document_generator, outline_generator, or similar tools.", correlationId);
                        context.addObservation(Observation.builder()
                                .observationId(UUID.randomUUID().toString())
                                .content("Artifact generation requested without a tool action. Planner must explicitly use document_generator, outline_generator, or similar tools to create artifacts.")
                                .success(false)
                                .build());
                        contextManager.updateContext(context);
                        // Continue to next iteration for re-planning
                    }
                    break;

                case REVIEW:
                    log.info("[{}] Agent state: REVIEW for execution: {}", correlationId, context.getExecutionId());
                    // Review and improve artifacts using ArtifactReviewer
                    if (!context.getArtifactReferences().isEmpty()) {
                        ArtifactReference latestArtifactRef = context.getArtifactReferences().get(context.getArtifactReferences().size() - 1);
                        log.info("[{}] Reviewing latest artifact reference: id={}, type={}, name={}", 
                                correlationId, latestArtifactRef.getArtifactId(), latestArtifactRef.getType(), latestArtifactRef.getName());
                        
                        // Fetch full artifact from ArtifactManager for review
                        Artifact latestArtifact = artifactManager.getArtifact(latestArtifactRef.getArtifactId());
                        if (latestArtifact != null) {
                            // Use ArtifactReviewer to generate improved version
                            Artifact reviewedArtifact = artifactReviewer.review(latestArtifact, context);
                            context.addArtifactReference(ArtifactReference.builder()
                                    .artifactKey(latestArtifactRef.getArtifactKey())
                                    .artifactId(reviewedArtifact.getArtifactId())
                                    .name(reviewedArtifact.getName())
                                    .type(reviewedArtifact.getType())
                                    .version(reviewedArtifact.getVersion())
                                    .status(ArtifactReference.ArtifactStatus.COMPLETED)
                                    .milestone(context.getCurrentMilestone())
                                    .parentArtifactKey(latestArtifactRef.getArtifactKey())
                                    .build());
                            contextManager.updateContext(context);
                            
                            log.info("[{}] Artifact review complete: original_id={}, reviewed_id={}, new_version={}", 
                                    correlationId, latestArtifact.getArtifactId(), reviewedArtifact.getArtifactId(), reviewedArtifact.getVersion());
                        } else {
                            log.warn("[{}] Could not fetch artifact {} for review", correlationId, latestArtifactRef.getArtifactId());
                        }
                    } else {
                        log.warn("[{}] No artifacts available for review in execution: {}", correlationId, context.getExecutionId());
                    }
                    break;

                default:
                    log.warn("[{}] Unknown agent state: {} for execution: {}", 
                            correlationId, effectiveState, context.getExecutionId());
                    execution.setCompletedAt(LocalDateTime.now());
                    return buildSuccessResponse(context, actionsTaken, toolCalls, 
                            planningResult.getReasoning(), planningResult, startedAt, executionGraph);
                }
            }

            // Max iterations reached - check if we can extend for complex workflows
            int completedMilestones = context.getCompletedMilestones().size();
            int totalMilestones = workflow.size();
            double completionRatio = totalMilestones > 0 ? (double) completedMilestones / totalMilestones : 0;
            
            // P0-3: Return proper failure status when max iterations reached
            log.warn("[{}] Max iterations reached for execution: {} with {:.0%} milestone completion", 
                    correlationId, context.getExecutionId(), completionRatio);
            execution.setCompletedAt(LocalDateTime.now());
            
            String completionMessage = String.format(
                    "MAX_ITERATIONS_REACHED: Execution stopped after %d iterations with %.0f%% milestone completion. %d artifacts generated. Current milestone: %s",
                    iteration, completionRatio * 100, context.getArtifactReferences().size(), context.getCurrentMilestone());
            
            // P1: Mark context as FAILED instead of COMPLETED
            if (context != null) {
                contextManager.markContextFailed(context.getExecutionId(), completionMessage);
            }
            
            return buildMaxIterationsResponse(context, actionsTaken, toolCalls, 
                    completionMessage, startedAt, executionGraph, completionRatio);

        } catch (Exception e) {
            log.error("[{}] Error during agent execution for goal: {}", correlationId, request.getGoal(), e);
            if (context != null) {
                contextManager.markContextFailed(context.getExecutionId(), e.getMessage());
            }
            return buildErrorResponse(e.getMessage(), startedAt);
        } finally {
            // Clean up context (but don't mark as completed - that's done above based on outcome)
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

    private AgentAction executeAction(AgentAction action, ExecutionContext context, ExecutionState state, List<ToolCall> toolCalls, String correlationId) {
        log.info("[{}] Executing action: {} with type: {}", correlationId, action.getActionId(), action.getType());

        // Handle action based on type
        if (action.getType() == null) {
            // Default to TOOL_CALL for backward compatibility
            action.setType(ActionType.TOOL_CALL);
        }

        switch (action.getType()) {
            case TOOL_CALL:
                return executeToolCall(action, context, state, toolCalls, correlationId);
            case STATE_TRANSITION:
                return handleStateTransition(action, context, correlationId);
            case COMPLETE:
                return handleComplete(action, context, correlationId);
            case NO_OP:
                return handleNoOp(action, context, correlationId);
            default:
                log.warn("[{}] Unknown action type: {}, defaulting to TOOL_CALL", correlationId, action.getType());
                action.setType(ActionType.TOOL_CALL);
                return executeToolCall(action, context, state, toolCalls, correlationId);
        }
    }

    private AgentAction executeToolCall(AgentAction action, ExecutionContext context, ExecutionState state, List<ToolCall> toolCalls, String correlationId) {
        log.info("[{}] Executing tool call: {} with tool: {}", correlationId, action.getActionId(), action.getToolName());

        // Validate tool exists before attempting execution
        if (!toolRegistry.hasTool(action.getToolName())) {
            log.error("[{}] Tool not found: {}. Skipping execution and marking action as failed.", correlationId, action.getToolName());
            action.setDescription(action.getDescription() + " (FAILED - TOOL NOT FOUND)");
            
            // Create error observation immediately without retries
            Observation errorObservation = Observation.builder()
                    .observationId(UUID.randomUUID().toString())
                    .toolName(action.getToolName())
                    .content("Tool not found: " + action.getToolName())
                    .timestamp(LocalDateTime.now())
                    .success(false)
                    .errorMessage("Tool not found: " + action.getToolName())
                    .build();
            state.addObservation(errorObservation);
            context.addObservation(errorObservation);
            
            // Create failed tool call record
            ToolCall toolCall = ToolCall.builder()
                    .callId(UUID.randomUUID())
                    .actionId(action.getActionId())
                    .toolName(action.getToolName())
                    .parameters(action.getParameters())
                    .result("")
                    .success(false)
                    .errorMessage("Tool not found: " + action.getToolName())
                    .startedAt(LocalDateTime.now())
                    .completedAt(LocalDateTime.now())
                    .durationMs(0)
                    .build();
            toolCalls.add(toolCall);
            
            return action;
        }

        // Check for duplicate actions before executing
        if (isDuplicateAction(action, state.getCompletedActions(), context)) {
            log.warn("[{}] Skipping duplicate action: {} with tool: {} and purpose: {}", 
                    correlationId, action.getActionId(), action.getToolName(), action.getPurpose());
            
            // Mark as skipped but return the action with modified description
            action.setDescription(action.getDescription() + " (SKIPPED - DUPLICATE)");
            
            // Add to completed actions so planner knows it was attempted
            state.addCompletedAction(action);
            
            // P1: Create structured tool result with SKIPPED_DUPLICATE outcome
            ToolResult skippedResult = ToolResult.builder()
                    .toolName(action.getToolName())
                    .success(false)
                    .result("Action skipped as duplicate")
                    .parameters(action.getParameters())
                    .errorMessage("Duplicate action skipped")
                    .durationMs(0)
                    .outcome(com.enterprise.ai.agent.model.ToolOutcome.SKIPPED_DUPLICATE)
                    .build();
            context.addToolResult(skippedResult);
            
            // Create observation to inform planner why action was skipped
            Observation skipObservation = Observation.builder()
                    .observationId(UUID.randomUUID().toString())
                    .toolName(action.getToolName())
                    .content("Action skipped as duplicate - this tool was already executed with similar parameters. Consider using a different tool or approach.")
                    .timestamp(LocalDateTime.now())
                    .success(false)
                    .errorMessage("Duplicate action skipped")
                    .build();
            state.addObservation(skipObservation);
            context.addObservation(skipObservation);
            
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
        LocalDateTime toolCompletedAt = LocalDateTime.now();
        long durationMs = 0;
        int attempt = 0;
        Exception lastException = null;

        // P0: Enforce search budget for knowledge_search tool
        if (action.getToolName().equals("knowledge_search")) {
            int currentKnowledgeSearchCount = (int) context.getToolResults().stream()
                    .filter(tr -> tr.getToolName().equals("knowledge_search"))
                    .filter(tr -> Boolean.TRUE.equals(tr.isSuccess()))
                    .filter(tr -> tr.getOutcome() == null || tr.getOutcome() != ToolOutcome.SKIPPED_DUPLICATE)
                    .count();
            
            if (currentKnowledgeSearchCount >= maxKnowledgeSearches) {
                log.warn("[{}] Knowledge search budget exhausted: {}/{}. Skipping tool execution.", 
                        correlationId, currentKnowledgeSearchCount, maxKnowledgeSearches);
                
                // Create a skipped result
                ToolResult skippedResult = ToolResult.builder()
                        .toolName(action.getToolName())
                        .success(false)
                        .result("Search budget exhausted. Maximum " + maxKnowledgeSearches + " knowledge searches allowed.")
                        .errorMessage("Search budget exceeded")
                        .parameters(action.getParameters())
                        .outcome(ToolOutcome.FAILED)
                        .build();
                
                state.addToolResult(skippedResult);
                state.addCompletedAction(action);
                context.addToolResult(skippedResult);
                
                // Add observation about budget exhaustion
                Observation budgetObservation = Observation.builder()
                        .observationId(UUID.randomUUID().toString())
                        .content("Knowledge search budget exhausted: " + currentKnowledgeSearchCount + "/" + maxKnowledgeSearches + " searches performed. Proceeding with available information.")
                        .timestamp(LocalDateTime.now())
                        .build();
                state.addObservation(budgetObservation);
                context.addObservation(budgetObservation);
                
                return action;
            }
        }

        while (attempt <= maxRetries) {
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
                toolCompletedAt = LocalDateTime.now();
                durationMs = java.time.Duration.between(toolStartedAt, toolCompletedAt).toMillis();
                
                // Add parameters to result for tracking
                result.setParameters(action.getParameters());
                
                // P1: Set structured outcome based on result
                if (result.isSuccess()) {
                    result.setOutcome(com.enterprise.ai.agent.model.ToolOutcome.SUCCESS);
                    
                    // Check for knowledge gaps in the result
                    if (result.getResult() != null) {
                        String resultLower = result.getResult().toLowerCase();
                        if (resultLower.contains("no information") || 
                            resultLower.contains("not found") ||
                            resultLower.contains("does not contain") ||
                            resultLower.contains("no results")) {
                            result.setOutcome(com.enterprise.ai.agent.model.ToolOutcome.KNOWLEDGE_GAP);
                        }
                    }
                } else {
                    result.setOutcome(com.enterprise.ai.agent.model.ToolOutcome.FAILED);
                }
                
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

                // CRITICAL FIX: Only add to completedActions if successful
                if (result.isSuccess()) {
                    state.addCompletedAction(action);
                } else {
                    state.addFailedAction(action);
                }

                // CRITICAL FIX: Also add ToolResult to ExecutionContext for WorkflowManager to see
                context.addToolResult(result);

                // DIAGNOSTIC: Log ToolResult registration
                log.info("[{}] TOOL_RESULT_REGISTERED toolName={} success={} outcome={} contextToolResults={}",
                        correlationId, result.getToolName(), result.isSuccess(), result.getOutcome(), context.getToolResults().size());

                // Process tool result centrally (register artifacts, observations, knowledge)
                processToolResult(context, result, action);

                // DIAGNOSTIC: Log context tool results after processing
                log.info("[{}] CONTEXT_TOOL_RESULTS size={} latestTool={} names={}",
                        correlationId, context.getToolResults().size(), result.getToolName(),
                        context.getToolResults().stream().map(ToolResult::getToolName).toList());

                // NOTE: Milestone advancement is now centralized in evaluateAndAdvanceMilestone
                // This is called after tool execution in the main loop, not here

                actionStatus = ActionStatus.COMPLETED;

                log.info("[{}] Action {} completed on attempt {} (success={})", correlationId, action.getActionId(), attempt, result.isSuccess());
                return action;

            } catch (Exception e) {
                lastException = e;
                log.error("[{}] Error executing action: {} on attempt {}/{}", correlationId, action.getActionId(), attempt, maxRetries, e);
                
                // Classify error to determine if retry is appropriate
                ErrorClassification classification = classifyError(e);
                
                // Add retry history
                context.addRetry(ExecutionContext.RetryHistory.builder()
                        .stepId(action.getActionId().toString())
                        .attempt(attempt)
                        .reason(e.getMessage())
                        .timestamp(LocalDateTime.now())
                        .build());
                
                // Skip retries for non-retriable errors
                if (classification == ErrorClassification.NON_RETRIABLE) {
                    log.warn("[{}] Non-retriable error detected for action {}: {}. Aborting retries.", 
                            correlationId, action.getActionId(), e.getMessage());
                    break;
                }
                
                // If we haven't exhausted retries, wait and try again
                if (attempt < maxRetries) {
                    try {
                        long delayMs = calculateRetryDelay(classification, attempt);
                        log.info("[{}] Retrying action {} in {} ms (classification: {})", 
                                correlationId, action.getActionId(), delayMs, classification);
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ie) {
                        log.warn("[{}] Action retry interrupted for action: {}", correlationId, action.getActionId());
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        // All retries exhausted
        log.error("[{}] Action {} failed after {} attempts", correlationId, action.getActionId(), maxRetries);
        actionStatus = ActionStatus.FAILED;

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

        // Create error observation
        Observation errorObservation = Observation.builder()
                .observationId(UUID.randomUUID().toString())
                .toolName(action.getToolName())
                .content("Failed to execute tool after " + maxRetries + " attempts: " + 
                        (lastException != null ? lastException.getMessage() : "Unknown error"))
                .timestamp(LocalDateTime.now())
                .success(false)
                .errorMessage(lastException != null ? lastException.getMessage() : "Unknown error")
                .build();
        state.addObservation(errorObservation);
        context.addObservation(errorObservation);

        return action;
    }

    private AgentAction handleStateTransition(AgentAction action, ExecutionContext context, String correlationId) {
        log.info("[{}] Handling state transition: {}", correlationId, action.getActionId());
        // TODO: Implement state transition logic
        return action;
    }

    private AgentAction handleComplete(AgentAction action, ExecutionContext context, String correlationId) {
        log.info("[{}] Handling complete action: {}", correlationId, action.getActionId());
        // TODO: Implement complete logic
        return action;
    }

    private AgentAction handleNoOp(AgentAction action, ExecutionContext context, String correlationId) {
        log.info("[{}] Handling NO_OP action: {}", correlationId, action.getActionId());
        // NO_OP is a placeholder - no action needed
        return action;
    }

    /**
     * Process tool result centrally - register artifacts, observations, and knowledge
     */
    private void processToolResult(ExecutionContext context, ToolResult result, AgentAction action) {
        if (result == null) {
            return;
        }

        registerArtifacts(context, result);
        registerObservations(context, result);
        registerKnowledge(context, result, action);
    }

    /**
     * Register artifacts from tool result to execution context
     */
    private void registerArtifacts(ExecutionContext context, ToolResult result) {
        if (result.getArtifacts() == null || result.getArtifacts().isEmpty()) {
            return;
        }

        for (ArtifactReference artifact : result.getArtifacts()) {
            context.addArtifactReference(artifact);
            log.info("Registered artifact {} v{} for execution {}",
                    artifact.getName(), artifact.getVersion(), context.getExecutionId());
        }
    }

    /**
     * Register observation from tool result to execution context
     */
    private void registerObservations(ExecutionContext context, ToolResult result) {
        if (result.getResult() == null || result.getResult().isEmpty()) {
            return;
        }

        Observation observation = Observation.builder()
                .observationId(UUID.randomUUID().toString())
                .toolName(result.getToolName())
                .content(result.getResult())
                .timestamp(LocalDateTime.now())
                .success(result.isSuccess())
                .errorMessage(result.getErrorMessage())
                .build();

        context.addObservation(observation);
    }

    /**
     * Check if there are failed required tool actions
     */
    private boolean hasFailedRequiredActions(ExecutionContext context, WorkflowManager workflowManager) {
        // Check for failed tool results
        boolean hasFailedTools = context.getToolResults().stream()
                .anyMatch(tr -> !tr.isSuccess());

        if (hasFailedTools) {
            // Log which tools failed
            context.getToolResults().stream()
                    .filter(tr -> !tr.isSuccess())
                    .forEach(tr -> log.warn("[{}] Failed tool detected: toolName={}, errorMessage={}",
                            context.getExecutionId(), tr.getToolName(), tr.getErrorMessage()));
            return true;
        }

        return false;
    }

    /**
     * Register knowledge from tool result (filtered to exclude execution events)
     */
    private void registerKnowledge(ExecutionContext context, ToolResult result, AgentAction action) {
        if (!result.isSuccess() || result.getResult() == null || result.getResult().isEmpty()) {
            return;
        }

        String correlationId = (String) context.getVariable("correlationId");

        // Filter out execution events - don't store "generated successfully" messages as knowledge
        String resultContent = result.getResult().toLowerCase();
        if (resultContent.contains("generated successfully") || 
            resultContent.contains("completed") ||
            resultContent.contains("skipped") ||
            resultContent.contains("failed")) {
            log.debug("[{}] Skipping knowledge storage for execution event: {}", correlationId, result.getToolName());
            return;
        }

        try {
            knowledgeMemory.storeKnowledge(
                    result.getResult(),
                    action.getToolName(),
                    action.getParameters().containsKey("query") ? 
                            action.getParameters().get("query").toString() : "unknown",
                    "fact",
                    context.getExecutionId()
            );
            log.debug("[{}] Stored knowledge from tool result: {}", correlationId, action.getToolName());
        } catch (Exception ke) {
            log.warn("[{}] Failed to store knowledge from tool result: {}", correlationId, action.getToolName(), ke);
        }
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
                .executionStatus(com.enterprise.ai.agent.model.ExecutionStatus.COMPLETED)
                .actionsTaken(convertToLegacyActions(actionsTaken))
                .toolCalls(toolCalls)
                .observations(context.getObservations())
                .artifacts(context.getArtifactReferences().stream()
                        .map(ref -> {
                            // Convert ArtifactReference to Artifact for backward compatibility
                            // TODO: Update AgentResponse to use ArtifactReference directly
                            return com.enterprise.ai.agent.model.Artifact.builder()
                                    .artifactId(ref.getArtifactId())
                                    .name(ref.getName())
                                    .type(ref.getType())
                                    .version(ref.getVersion())
                                    .build();
                        })
                        .collect(java.util.stream.Collectors.toList()))
                .executionGraph(executionGraph)
                .status("completed")
                .plannerReasoning(planningResult != null ? planningResult.getReasoning() : null)
                .plannerDecision(planningResult != null ? planningResult.getNextState().name() : null)
                .plannerNextStep(planningResult != null ? planningResult.getNextStep() : null)
                .plannerConfidence(planningResult != null ? planningResult.getConfidence() : null)
                .startedAt(startedAt)
                .completedAt(completedAt)
                .durationMs(durationMs)
                .build();
    }

    /**
     * P0-3: Build response for max iterations reached scenario
     * Returns proper failure status instead of misleading "completed"
     */
    private AgentResponse buildMaxIterationsResponse(ExecutionContext context, List<AgentAction> actionsTaken, 
            List<ToolCall> toolCalls, String errorMessage, LocalDateTime startedAt, ExecutionGraph executionGraph, double completionRatio) {
        LocalDateTime completedAt = LocalDateTime.now();
        long durationMs = java.time.Duration.between(startedAt, completedAt).toMillis();

        // Update graph metadata
        if (executionGraph != null) {
            executionGraph.getMetadata().setEndTime(completedAt.toString());
            executionGraph.getMetadata().setDurationMs(durationMs);
            executionGraph.getMetadata().setTotalNodes(executionGraph.getNodes().size());
            executionGraph.getMetadata().setTotalEdges(executionGraph.getEdges().size());
            executionGraph.getMetadata().setStatus("MAX_ITERATIONS");
        }

        return AgentResponse.builder()
                .executionId(context.getExecutionId())
                .goal(context.getGoal())
                .answer(null)
                .completed(false)
                .executionStatus(com.enterprise.ai.agent.model.ExecutionStatus.MAX_ITERATIONS)
                .actionsTaken(convertToLegacyActions(actionsTaken))
                .toolCalls(toolCalls)
                .observations(context.getObservations())
                .artifacts(context.getArtifactReferences().stream()
                        .map(ref -> {
                            return com.enterprise.ai.agent.model.Artifact.builder()
                                    .artifactId(ref.getArtifactId())
                                    .name(ref.getName())
                                    .type(ref.getType())
                                    .version(ref.getVersion())
                                    .build();
                        })
                        .collect(java.util.stream.Collectors.toList()))
                .executionGraph(executionGraph)
                .status("MAX_ITERATIONS")
                .errorMessage(errorMessage)
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
                .executionStatus(com.enterprise.ai.agent.model.ExecutionStatus.FAILED)
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
     * Create fallback planning result when planner fails
     */
    private PlanningResult createFallbackPlanningResult(ExecutionContext context) {
        log.warn("Creating fallback planning result for execution: {}", context.getExecutionId());
        
        // Determine appropriate fallback state based on current milestone
        String currentMilestone = context.getCurrentMilestone();
        AgentState fallbackState = AgentState.FINISH;
        String fallbackStep = "Execution completed with fallback";
        
        if (currentMilestone != null) {
            if (currentMilestone.contains("Collect") || currentMilestone.contains("Gather")) {
                fallbackState = AgentState.ANALYZE;
                fallbackStep = "Analyze collected information with fallback";
            } else if (currentMilestone.contains("Generate") || currentMilestone.contains("Write")) {
                fallbackState = AgentState.GENERATE_ARTIFACT;
                fallbackStep = "Generate artifact with fallback";
            }
        }
        
        return PlanningResult.builder()
                .reasoning("Fallback result due to planning failure. Attempting to continue execution.")
                .nextState(fallbackState)
                .milestone(currentMilestone)
                .nextStep(fallbackStep)
                .confidence(0.3) // Lower confidence for fallback
                .actions(new ArrayList<>())
                .build();
    }
    
    /**
     * Validate that the execution has produced artifacts that match the goal requirements
     */
    private boolean validateGoalCompletion(ExecutionContext context, String goal) {
        List<ArtifactReference> artifactReferences = context.getArtifactReferences();
        String goalLower = goal.toLowerCase();
        
        log.info("Validating goal completion for goal: {} with {} artifacts", goal, artifactReferences.size());
        
        // Check for thesis/document goals
        if (goalLower.contains("thesis") || goalLower.contains("document") || goalLower.contains("paper")) {
            boolean hasDocument = artifactReferences.stream().anyMatch(ref -> 
                "document".equalsIgnoreCase(ref.getType()) || 
                "file".equalsIgnoreCase(ref.getType()) ||
                ref.getName().toLowerCase().contains("thesis") ||
                ref.getName().toLowerCase().contains("document")
            );
            
            if (!hasDocument) {
                log.warn("[{}] Goal validation failed: No document artifact found for thesis/document goal", ERR_GOAL_VALIDATION);
                return false;
            }
        }
        
        // Check for outline goals
        if (goalLower.contains("outline")) {
            boolean hasOutline = artifactReferences.stream().anyMatch(ref -> 
                "outline".equalsIgnoreCase(ref.getType()) || 
                "file".equalsIgnoreCase(ref.getType()) ||
                ref.getName().toLowerCase().contains("outline")
            );
            
            if (!hasOutline) {
                log.warn("[{}] Goal validation failed: No outline artifact found for outline goal", ERR_GOAL_VALIDATION);
                return false;
            }
        }
        
        // Check for report goals
        if (goalLower.contains("report") || goalLower.contains("analysis")) {
            boolean hasReport = artifactReferences.stream().anyMatch(ref -> 
                "report".equalsIgnoreCase(ref.getType()) || 
                "document".equalsIgnoreCase(ref.getType()) ||
                "file".equalsIgnoreCase(ref.getType()) ||
                ref.getName().toLowerCase().contains("report")
            );
            
            if (!hasReport) {
                log.warn("[{}] Goal validation failed: No report artifact found for report/analysis goal", ERR_GOAL_VALIDATION);
                return false;
            }
        }
        
        // If no specific artifact type required, ensure at least some artifact was created
        if (artifactReferences.isEmpty()) {
            log.warn("[{}] Goal validation failed: No artifacts created", ERR_GOAL_VALIDATION);
            return false;
        }
        
        log.info("Goal validation passed: {} artifacts match goal requirements", artifactReferences.size());
        return true;
    }

    /**
     * P1-4: Check if previous knowledge search was successful.
     * Returns false if the search result indicates no information was found.
     */
    private boolean wasPreviousSearchSuccessful(AgentAction completedAction, ExecutionContext context) {
        // Find the corresponding tool result for this action
        for (ToolResult toolResult : context.getToolResults()) {
            if (toolResult.getToolName().equals("knowledge_search") && 
                toolResult.getParameters() != null &&
                toolResult.getParameters().equals(completedAction.getParameters())) {
                
                // Check if result indicates no information found
                if (toolResult.getResult() != null) {
                    String resultLower = toolResult.getResult().toLowerCase();
                    if (resultLower.contains("no information") || 
                        resultLower.contains("not found") ||
                        resultLower.contains("does not contain") ||
                        resultLower.contains("no results")) {
                        return false; // Previous search failed to find information
                    }
                }
                
                // If result is empty or very short, consider it unsuccessful
                if (toolResult.getResult() == null || toolResult.getResult().length() < 50) {
                    return false;
                }
                
                return true; // Previous search found useful information
            }
        }
        
        // If we can't find the tool result, be conservative and allow the search
        return false;
    }

    /**
     * Check if an action is a duplicate of previously completed actions.
     * Uses purpose and tool name to detect similar actions.
     */
    private boolean isDuplicateAction(AgentAction newAction, List<AgentAction> completedActions, ExecutionContext context) {
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

        // P1-4: Check by parameter similarity (for knowledge_search) with smarter logic
        // Allow reformulation when previous search failed to find information
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
                    
                    // Higher threshold (0.95) for exact duplicates
                    if (similarity >= 0.95) {
                        log.debug("Exact duplicate detected by query similarity: {}% (new: {}, completed: {})", 
                                (int)(similarity * 100), newQuery, completedQuery);
                        return true;
                    }
                    
                    // For 80-95% similarity, check if previous search was successful
                    if (similarity >= duplicateSimilarityThreshold && similarity < 0.95) {
                        // Check if previous search found useful information
                        if (!wasPreviousSearchSuccessful(completedAction, context)) {
                            log.debug("Allowing reformulation: previous search with {}% similarity failed to find useful information", 
                                    (int)(similarity * 100));
                            return false; // Allow reformulation
                        }
                        
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
    
    /**
     * Classify error to determine retry strategy
     */
    private ErrorClassification classifyError(Exception e) {
        String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String exceptionType = e.getClass().getSimpleName().toLowerCase();
        
        // Non-retriable errors
        if (exceptionType.contains("illegalargument") || 
            errorMessage.contains("tool not found") ||
            errorMessage.contains("invalid parameter") ||
            errorMessage.contains("authentication") ||
            errorMessage.contains("authorization") ||
            errorMessage.contains("permission")) {
            return ErrorClassification.NON_RETRIABLE;
        }
        
        // Rate limit errors
        if (errorMessage.contains("rate limit") || 
            errorMessage.contains("too many requests") ||
            errorMessage.contains("429")) {
            return ErrorClassification.RATE_LIMIT;
        }
        
        // Timeout errors
        if (exceptionType.contains("timeout") || 
            errorMessage.contains("timeout") ||
            errorMessage.contains("timed out")) {
            return ErrorClassification.TIMEOUT;
        }
        
        // Default to retriable for unknown errors
        return ErrorClassification.RETRIABLE;
    }
    
    /**
     * Calculate retry delay based on error classification
     */
    private long calculateRetryDelay(ErrorClassification classification, int attempt) {
        switch (classification) {
            case RATE_LIMIT:
                // Longer delay for rate limits
                return retryDelayMs * (long) Math.pow(3, attempt - 1);
            case TIMEOUT:
                // Moderate delay for timeouts
                return retryDelayMs * (long) Math.pow(2, attempt - 1);
            case RETRIABLE:
                // Standard exponential backoff
                return retryDelayMs * (long) Math.pow(2, attempt - 1);
            case NON_RETRIABLE:
            default:
                return 0;
        }
    }
    
    /**
     * P0-1: Evaluate milestone completion and advance if complete.
     * This is called AFTER tool execution to ensure milestone progression
     * happens before the planner is called again.
     * 
     * @return true if workflow is complete, false otherwise
     */
    private boolean evaluateAndAdvanceMilestone(ExecutionContext context, ExecutionState state, 
                                                 List<String> workflow, String correlationId,
                                                 ExecutionGraph executionGraph, LocalDateTime startedAt,
                                                 List<AgentAction> actionsTaken, List<ToolCall> toolCalls) {
        String currentMilestone = context.getCurrentMilestone();
        if (currentMilestone == null || "Complete".equals(currentMilestone)) {
            return false;
        }
        
        boolean milestoneComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, state.getCompletedActions().size());
        
        if (milestoneComplete) {
            log.info("[{}] Milestone '{}' is complete after tool execution. Advancing to next milestone.", 
                    correlationId, currentMilestone);
            
            // Update progress tracking
            context.setVariable("lastProgressTimestamp", System.currentTimeMillis());
            
            // Add to completed milestones
            context.addCompletedMilestone(currentMilestone);
            
            // Get next milestone
            String nextMilestone = workflowManager.getNextMilestone(workflow, currentMilestone);
            
            // Add milestone advancement to execution graph
            Object graphObj = context.getMetadata().get("executionGraph");
            if (graphObj instanceof ExecutionGraph) {
                addMilestoneNode(nextMilestone, (ExecutionGraph) graphObj, context.getCurrentStep());
            }
            
            if (nextMilestone != null) {
                context.setCurrentMilestone(nextMilestone);
                log.info("[{}] Advanced to next milestone: {}", correlationId, nextMilestone);
                
                // Set milestone completion criteria in context for planner
                context.setVariable("milestoneCriteria", workflowManager.getMilestoneCriteria(nextMilestone));
                
                // Check if we reached Complete milestone
                if ("Complete".equals(nextMilestone)) {
                    log.info("[{}] Reached final milestone: Complete. Finishing execution.", correlationId);
                    return true;
                }
            } else {
                log.info("[{}] No next milestone available. Finishing execution.", correlationId);
                return true;
            }
            
            contextManager.updateContext(context);
        }
        
        return false;
    }
}
