package com.enterprise.ai.agent.tools.impl;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.Observation;
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
public class DocumentGeneratorTool implements Tool {

    private final ChatClient chatClient;
    private final ArtifactManager artifactManager;

    public DocumentGeneratorTool(ChatClient chatClient, ArtifactManager artifactManager) {
        this.chatClient = chatClient;
        this.artifactManager = artifactManager;
    }

    @Override
    public String name() {
        return "document_generator";
    }

    @Override
    public String description() {
        return "Generate documents using LLM based on provided content and instructions";
    }

    @Override
    public ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager) {
        long startTime = System.currentTimeMillis();
        log.info("Executing document_generator tool with request: {}", request);

        try {
            // Accept multiple parameter names for flexibility
            String content = (String) request.getParameters().get("content");
            if (content == null || content.trim().isEmpty()) {
                content = (String) request.getParameters().get("topic");
            }
            if (content == null || content.trim().isEmpty()) {
                content = (String) request.getParameters().get("query");
            }
            
            // Fallback to use observations if no content provided
            if (content == null || content.trim().isEmpty()) {
                if (!context.getObservations().isEmpty()) {
                    content = String.join("\n", context.getObservations().stream()
                            .map(Observation::getContent)
                            .toArray(String[]::new));
                    log.warn("No content parameter found, using observations as content");
                } else {
                    content = context.getGoal();
                    log.warn("No content parameter found, using goal: {}", content);
                }
            }
            
            String instructions = (String) request.getParameters().get("instructions");
            String documentType = (String) request.getParameters().getOrDefault("documentType", "general");

            if (content == null || content.trim().isEmpty()) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .result("Content parameter is required")
                        .errorMessage("Content parameter is required")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Build prompt for document generation
            String prompt = buildDocumentPrompt(content, instructions, documentType);

            // Generate document using LLM
            String generatedDocument = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Create artifact
            String artifactName = generateArtifactName(documentType, context.getCurrentMilestone());
            artifactManager.createArtifact(
                    "document",
                    artifactName,
                    generatedDocument,
                    "text/markdown",
                    "document_generator",
                    context.getExecutionId()
            );

            Map<String, Object> data = new HashMap<>();
            data.put("documentType", documentType);
            data.put("artifactName", artifactName);
            data.put("contentLength", generatedDocument.length());

            return ToolResult.builder()
                    .toolName(name())
                    .success(true)
                    .result("Document generated successfully: " + artifactName)
                    .data(data)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (Exception e) {
            log.error("Error executing document_generator tool", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to generate document")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private String buildDocumentPrompt(String content, String instructions, String documentType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document generator. Generate a ");
        prompt.append(documentType);
        prompt.append(" document based on the following content.\n\n");
        
        if (instructions != null && !instructions.trim().isEmpty()) {
            prompt.append("Instructions: ").append(instructions).append("\n\n");
        }
        
        prompt.append("Content:\n");
        prompt.append(content);
        prompt.append("\n\n");
        prompt.append("Generate a well-structured document in Markdown format. ");
        prompt.append("Include appropriate headings, sections, and formatting.");
        
        return prompt.toString();
    }

    private String generateArtifactName(String documentType, String milestone) {
        String baseName = documentType.toLowerCase().replaceAll("\\s+", "_");
        if (milestone != null && !milestone.isEmpty()) {
            baseName = milestone.toLowerCase().replaceAll("\\s+", "_");
        }
        return baseName + ".md";
    }
}
