package com.enterprise.ai.agent.planner;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.model.PlanningResult;

public interface Planner {
    PlanningResult createPlan(ExecutionContext context);
}
