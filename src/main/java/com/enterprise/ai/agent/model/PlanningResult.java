package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * PlanningResult - Produced only by the planner.
 * Contains the reasoning, current state, next state, milestone, next step, confidence, and list of actions.
 * The planner is stateless and does not own execution state.
 * The runtime owns action status, execution metadata, and lifecycle management.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanningResult {
    private String reasoning;
    private AgentState currentState;
    private AgentState nextState;
    private String milestone;
    private String nextStep;
    private double confidence;
    private List<AgentAction> actions;
}
