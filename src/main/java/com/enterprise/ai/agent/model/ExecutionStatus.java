package com.enterprise.ai.agent.model;

/**
 * ExecutionStatus - Represents the status of an agent execution.
 * Provides distinct statuses to accurately reflect execution outcomes.
 */
public enum ExecutionStatus {
    /**
     * Execution is currently running
     */
    RUNNING,
    
    /**
     * Execution completed successfully
     */
    COMPLETED,
    
    /**
     * Execution failed due to an error
     */
    FAILED,
    
    /**
     * Execution was cancelled by user or system
     */
    CANCELLED,
    
    /**
     * Execution timed out
     */
    TIMED_OUT,
    
    /**
     * Execution reached maximum iterations without completing
     */
    MAX_ITERATIONS,
    
    /**
     * Execution is waiting for user input
     */
    WAITING_FOR_USER
}
