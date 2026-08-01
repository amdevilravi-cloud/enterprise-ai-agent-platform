package com.enterprise.ai.agent.model;

/**
 * PlannerDecision - Decision made by the planner about what to do next.
 * The planner is stateless and only provides the decision; the runtime owns execution.
 */
public enum PlannerDecision {
    EXECUTE_TOOL,
    RESPOND,
    ASK_USER,
    REPLAN,
    COMPLETE
}
