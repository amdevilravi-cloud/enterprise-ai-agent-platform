package com.enterprise.ai.agent.model;

/**
 * AgentState - Represents the current state in the agent execution lifecycle.
 * Replaces PlannerDecision to enable richer workflow orchestration.
 * The agent transitions through these states to complete complex workflows.
 */
public enum AgentState {
    /**
     * Initial state - agent is planning the next steps
     */
    PLAN,
    
    /**
     * Agent is executing tools to gather information or perform actions
     */
    EXECUTE,
    
    /**
     * Agent is observing and processing tool results
     */
    OBSERVE,
    
    /**
     * Agent is analyzing observations without executing tools
     * Pure reasoning step to synthesize information
     */
    ANALYZE,
    
    /**
     * Agent is generating artifacts (documents, outlines, theses, etc.)
     * Creation phase for deliverables
     */
    GENERATE_ARTIFACT,
    
    /**
     * Agent is reviewing and improving artifacts
     * Quality checking and revision phase
     */
    REVIEW,
    
    /**
     * Agent is preparing to respond to the user
     */
    RESPOND,
    
    /**
     * Execution is complete
     */
    FINISH
}
