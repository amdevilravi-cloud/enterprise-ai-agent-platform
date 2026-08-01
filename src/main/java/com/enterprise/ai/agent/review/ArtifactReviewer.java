package com.enterprise.ai.agent.review;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.Artifact;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * ArtifactReviewer - Service for reviewing and improving artifacts using LLM.
 * Generates new versions of artifacts with improvements based on review criteria.
 */
@Component
@Slf4j
public class ArtifactReviewer {
    
    private final ChatClient chatClient;
    private final ArtifactManager artifactManager;
    
    public ArtifactReviewer(ChatClient chatClient, ArtifactManager artifactManager) {
        this.chatClient = chatClient;
        this.artifactManager = artifactManager;
    }
    
    /**
     * Review an artifact and generate an improved version
     */
    public Artifact review(Artifact artifact, ExecutionContext context) {
        log.info("Reviewing artifact: id={}, type={}, name={}", 
                artifact.getArtifactId(), artifact.getType(), artifact.getName());
        
        try {
            String reviewPrompt = buildReviewPrompt(artifact, context);
            String improvedContent = chatClient.prompt()
                    .user(reviewPrompt)
                    .call()
                    .content();
            
            // Create new version with improved content
            Artifact improvedArtifact = artifactManager.updateArtifact(
                    artifact.getArtifactId(), 
                    improvedContent
            );
            
            log.info("Artifact review complete: old_id={}, new_id={}, new_version={}", 
                    artifact.getArtifactId(), improvedArtifact.getArtifactId(), improvedArtifact.getVersion());
            
            return improvedArtifact;
            
        } catch (Exception e) {
            log.error("Error reviewing artifact: {}", artifact.getArtifactId(), e);
            return artifact; // Return original if review fails
        }
    }
    
    /**
     * Build review prompt for LLM
     */
    private String buildReviewPrompt(Artifact artifact, ExecutionContext context) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("You are an expert reviewer. Review and improve the following ");
        prompt.append(artifact.getType());
        prompt.append(".\n\n");
        
        prompt.append("Artifact Name: ").append(artifact.getName()).append("\n");
        prompt.append("Current Content:\n");
        prompt.append(artifact.getContent());
        prompt.append("\n\n");
        
        // Add context from execution
        if (context.getGoal() != null) {
            prompt.append("Original Goal: ").append(context.getGoal()).append("\n");
        }
        
        if (context.getCurrentMilestone() != null) {
            prompt.append("Current Milestone: ").append(context.getCurrentMilestone()).append("\n");
        }
        
        // Add review criteria based on artifact type
        prompt.append("\nReview Criteria:\n");
        switch (artifact.getType().toLowerCase()) {
            case "outline":
                prompt.append("- Structure and organization\n");
                prompt.append("- Logical flow and completeness\n");
                prompt.append("- Clarity of headings and sections\n");
                prompt.append("- Coverage of required topics\n");
                break;
            case "document":
                prompt.append("- Clarity and coherence\n");
                prompt.append("- Grammar and style\n");
                prompt.append("- Accuracy of information\n");
                prompt.append("- Completeness of content\n");
                prompt.append("- Professional tone\n");
                break;
            default:
                prompt.append("- Quality and accuracy\n");
                prompt.append("- Clarity and completeness\n");
                prompt.append("- Relevance to goal\n");
        }
        
        prompt.append("\nProvide an improved version of the artifact. ");
        prompt.append("Maintain the same structure and format, but enhance the content based on the review criteria. ");
        prompt.append("Return only the improved content without any introductory or concluding remarks.");
        
        return prompt.toString();
    }
}
