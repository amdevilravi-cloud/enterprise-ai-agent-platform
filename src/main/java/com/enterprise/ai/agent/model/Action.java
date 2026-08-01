package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Action {

    private String actionId;

    private String toolName;

    private String description;

    private Map<String, Object> parameters;

    private String status;

    private long durationMs;
}
