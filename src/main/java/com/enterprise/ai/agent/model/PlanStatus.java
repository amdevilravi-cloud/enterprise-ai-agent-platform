package com.enterprise.ai.agent.model;

public enum PlanStatus {
    CREATED,
    WAITING_FOR_TOOL,
    EXECUTING,
    WAITING_FOR_REPLAN,
    COMPLETED,
    FAILED
}
