package com.enterprise.ai.agent.workflow;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.model.ArtifactReference;
import com.enterprise.ai.agent.model.ToolResult;
import com.enterprise.ai.agent.model.ToolOutcome;
import com.enterprise.ai.agent.tools.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Unit tests for milestone completion scenarios
 * Tests deterministic completion logic for different milestone types
 */
@ExtendWith(MockitoExtension.class)
public class WorkflowManagerTest {

    @Mock
    private ToolRegistry toolRegistry;

    private WorkflowManager workflowManager;

    @BeforeEach
    void setUp() {
        when(toolRegistry.getAllToolSchemas()).thenReturn(new java.util.HashMap<>());
        workflowManager = new WorkflowManager(toolRegistry);
    }

    @Test
    void testCollectInformationMilestone_WithSufficientSearches_ShouldComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Collect Information");
        addKnowledgeSearchResults(context, 2); // Minimum is 1

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertTrue(isComplete, "Collect Information milestone should complete with 2 knowledge searches");
    }

    @Test
    void testCollectInformationMilestone_WithInsufficientSearches_ShouldNotComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Collect Information");
        addKnowledgeSearchResults(context, 0); // Minimum is 1

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertFalse(isComplete, "Collect Information milestone should not complete with 0 knowledge searches");
    }

    @Test
    void testAnalyzeInformationMilestone_WithArtifact_ShouldComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Analyze Information");
        addKnowledgeSearchResults(context, 2); // Minimum is 1
        addArtifact(context, "analysis", "Analyze Information");

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 1);

        // Assert
        assertTrue(isComplete, "Analyze Information milestone should complete with artifact and searches");
    }

    @Test
    void testAnalyzeInformationMilestone_WithoutArtifact_ShouldNotComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Analyze Information");
        addKnowledgeSearchResults(context, 2); // Minimum is 1
        // No artifact added

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 1);

        // Assert
        assertFalse(isComplete, "Analyze Information milestone should not complete without artifact");
    }

    @Test
    void testGenerateOutlineMilestone_WithOutlineArtifact_ShouldComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Generate Outline");
        addArtifact(context, "outline", "Generate Outline");

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertTrue(isComplete, "Generate Outline milestone should complete with outline artifact");
    }

    @Test
    void testGenerateOutlineMilestone_WithoutArtifact_ShouldNotComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Generate Outline");
        // No artifact added

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertFalse(isComplete, "Generate Outline milestone should not complete without artifact");
    }

    @Test
    void testWriteDocumentMilestone_WithDocumentArtifact_ShouldComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Write Document");
        addArtifact(context, "document", "Write Document");

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertTrue(isComplete, "Write Document milestone should complete with document artifact");
    }

    @Test
    void testWriteDocumentMilestone_WithoutArtifact_ShouldNotComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Write Document");
        // No artifact added

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertFalse(isComplete, "Write Document milestone should not complete without artifact");
    }

    @Test
    void testReviewDocumentMilestone_WithReviewArtifact_ShouldComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Review Document");
        addArtifact(context, "review", "Review Document");

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 1);

        // Assert
        assertTrue(isComplete, "Review Document milestone should complete with review artifact");
    }

    @Test
    void testReviewDocumentMilestone_WithoutArtifact_ShouldNotComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Review Document");
        // No artifact added

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 1);

        // Assert
        assertFalse(isComplete, "Review Document milestone should not complete without artifact");
    }

    @Test
    void testCompleteMilestone_AlwaysComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Complete");

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertTrue(isComplete, "Complete milestone should always be complete");
    }

    @Test
    void testUnknownMilestone_WithAction_ShouldComplete() {
        // Arrange
        ExecutionContext context = createExecutionContext("Unknown Milestone");

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 1);

        // Assert
        assertTrue(isComplete, "Unknown milestone should complete with at least 1 action");
    }

    @Test
    void testKnowledgeSearchCount_IgnoresSkippedDuplicates() {
        // Arrange
        ExecutionContext context = createExecutionContext("Collect Information");
        addKnowledgeSearchResults(context, 1);
        addSkippedDuplicateSearch(context); // Should not count

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertTrue(isComplete, "Skipped duplicate searches should not count toward total");
    }

    @Test
    void testKnowledgeSearchCount_IgnoresFailedSearches() {
        // Arrange
        ExecutionContext context = createExecutionContext("Collect Information");
        addKnowledgeSearchResults(context, 1);
        addFailedSearch(context); // Should not count

        // Act
        boolean isComplete = workflowManager.isMilestoneCompleteUsingDefinition(context, 0);

        // Assert
        assertTrue(isComplete, "Failed searches should not count toward total");
    }

    // Helper methods

    private ExecutionContext createExecutionContext(String milestone) {
        ExecutionContext context = new ExecutionContext();
        context.setCurrentMilestone(milestone);
        context.setToolResults(new ArrayList<>());
        context.setArtifactReferences(new ArrayList<>());
        return context;
    }

    private void addKnowledgeSearchResults(ExecutionContext context, int count) {
        for (int i = 0; i < count; i++) {
            ToolResult result = ToolResult.builder()
                    .toolName("knowledge_search")
                    .success(true)
                    .outcome(null)
                    .build();
            context.getToolResults().add(result);
        }
    }

    private void addSkippedDuplicateSearch(ExecutionContext context) {
        ToolResult result = ToolResult.builder()
                .toolName("knowledge_search")
                .success(true)
                .outcome(ToolOutcome.SKIPPED_DUPLICATE)
                .build();
        context.getToolResults().add(result);
    }

    private void addFailedSearch(ExecutionContext context) {
        ToolResult result = ToolResult.builder()
                .toolName("knowledge_search")
                .success(false)
                .outcome(null)
                .build();
        context.getToolResults().add(result);
    }

    private void addArtifact(ExecutionContext context, String artifactKey, String milestone) {
        ArtifactReference artifact = ArtifactReference.builder()
                .artifactKey(artifactKey)
                .artifactId(java.util.UUID.randomUUID())
                .name("Test Artifact")
                .type(artifactKey)
                .version(1)
                .status(ArtifactReference.ArtifactStatus.CREATED)
                .milestone(milestone)
                .build();
        context.getArtifactReferences().add(artifact);
    }
}
