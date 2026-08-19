package com.enterprise.ai.agent.agent_runtime;

/**
 * Progress event types for tracking meaningful execution progress
 * Replaces stuck counters with event-driven progress tracking
 */
public enum ProgressType {
    TOOL_EXECUTED,
    ARTIFACT_CREATED,
    KNOWLEDGE_FOUND,
    KNOWLEDGE_GAP_CONFIRMED,
    MILESTONE_COMPLETED,
    STATE_CHANGED,
    ACTION_COMPLETED
}
