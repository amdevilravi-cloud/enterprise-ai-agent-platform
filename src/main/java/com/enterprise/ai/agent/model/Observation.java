package com.enterprise.ai.agent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Observation {

    private String observationId;

    private String toolName;

    private String content;

    private LocalDateTime timestamp;

    private boolean success;

    private String errorMessage;
}
