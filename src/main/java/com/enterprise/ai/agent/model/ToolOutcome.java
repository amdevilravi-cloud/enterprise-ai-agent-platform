package com.enterprise.ai.agent.model;

/**
 * ToolOutcome - Represents the outcome of a tool execution.
 * Provides structured outcomes to enable better workflow decision making.
 */
public enum ToolOutcome {
    /**
     * Tool executed successfully and produced useful results
     */
    SUCCESS,
    
    /**
     * Tool execution failed due to an error
     */
    FAILED,
    
    /**
     * Action was skipped as a duplicate of a previous action
     */
    SKIPPED_DUPLICATE,
    
    /**
     * Tool executed but returned no relevant results
     */
    NO_RELEVANT_RESULTS,
    
    /**
     * Tool execution revealed a knowledge gap (information not found)
     */
    KNOWLEDGE_GAP,
    
    /**
     * Tool execution was rate limited
     */
    RATE_LIMITED,
    
    /**
     * Tool execution timed out
     */
    TIMEOUT
}
