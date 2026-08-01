package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentPlan {

    private UUID planId;

    private String goal;

    private String reasoning;

    private List<AgentAction> actions;

    private boolean requiresToolExecution;

    private PlanStatus status;
}
