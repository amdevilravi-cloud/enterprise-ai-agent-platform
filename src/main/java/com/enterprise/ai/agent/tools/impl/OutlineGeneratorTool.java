package com.enterprise.ai.agent.tools.impl;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.ToolRequest;
import com.enterprise.ai.agent.model.ToolResult;
import com.enterprise.ai.agent.tools.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class OutlineGeneratorTool implements Tool {

    private final ChatClient chatClient;
    private final ArtifactManager artifactManager;

    public OutlineGeneratorTool(ChatClient chatClient, ArtifactManager artifactManager) {
        this.chatClient = chatClient;
        this.artifactManager = artifactManager;
    }

    @Override
    public String name() {
        return "outline_generator";
    }

    @Override
    public String description() {
        return "Generate structured outlines for documents, theses, or reports using LLM";
    }

    @Override
    public ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager) {
        long startTime = System.currentTimeMillis();
        log.info("Executing outline_generator tool with request: {}", request);

        try {
            // Accept multiple parameter names for flexibility
            String topic = (String) request.getParameters().get("topic");
            if (topic == null || topic.trim().isEmpty()) {
                topic = (String) request.getParameters().get("query");
            }
            if (topic == null || topic.trim().isEmpty()) {
                topic = (String) request.getParameters().get("subject");
            }
            
            // Fallback to context goal if no topic provided
            if (topic == null || topic.trim().isEmpty()) {
                topic = context.getGoal();
                log.warn("No topic parameter found, using goal: {}", topic);
            }
            
            String content = (String) request.getParameters().get("content");
            String outlineType = (String) request.getParameters().getOrDefault("outlineType", "general");

            if (topic == null || topic.trim().isEmpty()) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .result("Topic parameter is required")
                        .errorMessage("Topic parameter is required")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Build prompt for outline generation
            String prompt = buildOutlinePrompt(topic, content, outlineType);

            // Generate outline using LLM
            String generatedOutline = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Create artifact
            String artifactName = generateArtifactName(topic, context.getCurrentMilestone());
            artifactManager.createArtifact(
                    "outline",
                    artifactName,
                    generatedOutline,
                    "text/markdown",
                    "outline_generator",
                    context.getExecutionId()
            );

            Map<String, Object> data = new HashMap<>();
            data.put("topic", topic);
            data.put("outlineType", outlineType);
            data.put("artifactName", artifactName);
            data.put("contentLength", generatedOutline.length());

            return ToolResult.builder()
                    .toolName(name())
                    .success(true)
                    .result("Outline generated successfully: " + artifactName)
                    .data(data)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("Error executing outline_generator tool", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to generate outline")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private String buildOutlinePrompt(String topic, String content, String outlineType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an outline generator. Create a structured ");
        prompt.append(outlineType);
        prompt.append(" outline for the following topic.\n\n");
        
        prompt.append("Topic: ").append(topic).append("\n\n");
        
        if (content != null && !content.trim().isEmpty()) {
            prompt.append("Additional Content/Context:\n");
            prompt.append(content);
            prompt.append("\n\n");
        }
        
        prompt.append("Generate a well-structured outline in Markdown format. ");
        prompt.append("Use appropriate heading levels (##, ###, etc.) and include:");
        prompt.append("\n- Main sections");
        prompt.append("\n- Subsections with key points");
        prompt.append("\n- Logical flow and structure");
        prompt.append("\n- Clear hierarchy");
        
        return prompt.toString();
    }

    private String generateArtifactName(String topic, String milestone) {
        String baseName = topic.toLowerCase().replaceAll("[^a-z0-9\\s]", "").trim().replaceAll("\\s+", "_");
        if (milestone != null && !milestone.isEmpty()) {
            baseName = milestone.toLowerCase().replaceAll("\\s+", "_");
        }
        return baseName + "_outline.md";
    }
}
