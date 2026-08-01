package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Goal - Represents a user goal or objective for the agent.
 * Contains the goal description, context, and optional metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Goal {
    private UUID goalId;
    private String description;
    private String context;
    private Map<String, Object> metadata;
    private LocalDateTime createdAt;
}
