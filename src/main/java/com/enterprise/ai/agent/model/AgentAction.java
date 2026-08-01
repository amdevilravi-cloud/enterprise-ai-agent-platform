package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

/**
 * AgentAction - Action definition produced by the planner.
 * The planner only defines what to do (tool, description, parameters, purpose).
 * The runtime owns execution state (status, timestamps, retries).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentAction {
    private UUID actionId;
    private String toolName;
    private String description;
    private String purpose; // Purpose of this action (e.g., "Collect overview", "Analyze findings")
    private Map<String, Object> parameters;
}
