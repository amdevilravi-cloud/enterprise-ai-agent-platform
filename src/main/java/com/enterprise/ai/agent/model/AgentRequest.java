package com.enterprise.ai.agent.model;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRequest {

    @NotBlank(message = "Goal is required")
    private String goal;

    private String context;

    private String conversationId;

    private String knowledgeBaseId;
}
