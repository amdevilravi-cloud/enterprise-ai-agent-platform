package com.enterprise.ai.agent.model;

/**
 * ActionType - Defines the type of action to be executed.
 * Used instead of string-based tool names for state transitions.
 */
public enum ActionType {
    TOOL_CALL,           // Execute a tool
    STATE_TRANSITION,    // Transition between states
    COMPLETE,            // Complete execution
    NO_OP                // No operation (state transition placeholder)
}
