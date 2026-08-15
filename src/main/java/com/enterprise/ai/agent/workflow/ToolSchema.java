package com.enterprise.ai.agent.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ToolSchema - Defines the input/output contract for a tool.
 * Used by the planner to understand tool capabilities.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolSchema {
    private String name;
    private String description;
    private Map<String, ParameterSchema> parameters;
    private String produces;  // What artifact type this tool produces

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ParameterSchema {
        private String type;
        private boolean required;
    }

    public static class ToolSchemaBuilder {
        private Map<String, ParameterSchema> parameters = new HashMap<>();

        public ToolSchemaBuilder addParameter(String name, String type, boolean required) {
            this.parameters.put(name, ParameterSchema.builder()
                    .type(type)
                    .required(required)
                    .build());
            return this;
        }
    }
}
