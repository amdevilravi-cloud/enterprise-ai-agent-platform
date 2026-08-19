package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PlanningResult - Produced only by the planner.
 * Contains the reasoning, next state, milestone, next step, confidence, and list of actions.
 * The planner is stateless and does not own execution state.
 * The runtime owns current state and all execution metadata.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PlanningResult {
    private String reasoning;
    private AgentState nextState;
    private String milestone;
    private String nextStep;
    private double confidence;
    private List<AgentAction> actions;
}
