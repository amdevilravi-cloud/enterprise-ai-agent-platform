package com.enterprise.ai.agent.tools.impl;

import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.model.Artifact;
import com.enterprise.ai.agent.model.ToolRequest;
import com.enterprise.ai.agent.model.ToolResult;
import com.enterprise.ai.agent.tools.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * DocumentReviewerTool - Reviews and improves artifacts using LLM
 * Creates review artifacts with proper artifactKey="review" and artifactType="review"
 */
@Component
@Slf4j
public class DocumentReviewerTool implements Tool {

    private final ChatClient chatClient;

    @Autowired
    public DocumentReviewerTool(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @Override
    public String name() {
        return "document_reviewer";
    }

    @Override
    public String description() {
        return "Review and improve documents, theses, or reports using LLM analysis";
    }

    @Override
    public ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager) {
        try {
            String documentId = (String) request.getParameters().get("documentId");
            String reviewType = (String) request.getParameters().getOrDefault("reviewType", "comprehensive");
            
            if (documentId == null) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .errorMessage("documentId parameter is required")
                        .build();
            }

            // Fetch the document to review
            UUID documentUuid;
            try {
                documentUuid = UUID.fromString(documentId);
            } catch (IllegalArgumentException e) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .errorMessage("Invalid documentId format: " + documentId)
                        .build();
            }
            
            Artifact document = artifactManager.getArtifact(documentUuid);
            if (document == null) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .errorMessage("Document not found: " + documentId)
                        .build();
            }

            log.info("Reviewing document: id={}, type={}, reviewType={}", documentId, document.getType(), reviewType);

            // Build review prompt
            String prompt = buildReviewPrompt(document, reviewType);

            // Generate review using LLM
            String reviewContent = chatClient.prompt()
                    .user(prompt)
                    .call()
                    .content();

            // Create review artifact with proper contract
            Artifact reviewArtifact = artifactManager.createArtifact(
                    "review",
                    "review_" + document.getName(),
                    reviewContent,
                    "text/markdown",
                    "document_reviewer",
                    context.getExecutionId()
            );

            // Add review reference to context with proper artifactKey
            context.addArtifactReference(
                    com.enterprise.ai.agent.model.ArtifactReference.builder()
                            .artifactKey("review")
                            .artifactId(reviewArtifact.getArtifactId())
                            .name(reviewArtifact.getName())
                            .type("review")
                            .version(reviewArtifact.getVersion())
                            .status(com.enterprise.ai.agent.model.ArtifactReference.ArtifactStatus.COMPLETED)
                            .milestone(context.getCurrentMilestone())
                            .parentArtifactKey(document.getType().toLowerCase())
                            .build()
            );

            log.info("Document review complete: documentId={}, reviewId={}", documentId, reviewArtifact.getArtifactId());

            return ToolResult.builder()
                    .toolName(name())
                    .success(true)
                    .result("Review completed successfully. Review artifact created: " + reviewArtifact.getArtifactId())
                    .build();

        } catch (Exception e) {
            log.error("Error executing document review", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .errorMessage("Review failed: " + e.getMessage())
                    .build();
        }
    }

    private String buildReviewPrompt(Artifact document, String reviewType) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an expert document reviewer. Review the following ");
        prompt.append(document.getType());
        prompt.append(" and provide comprehensive feedback.\n\n");
        
        prompt.append("DOCUMENT CONTENT:\n");
        prompt.append(document.getContent());
        prompt.append("\n\n");
        
        prompt.append("REVIEW TYPE: ").append(reviewType).append("\n\n");
        
        prompt.append("Provide your review in the following format:\n");
        prompt.append("1. Summary of the document\n");
        prompt.append("2. Strengths\n");
        prompt.append("3. Areas for improvement\n");
        prompt.append("4. Specific recommendations\n");
        prompt.append("5. Overall assessment\n");
        
        return prompt.toString();
    }
}
