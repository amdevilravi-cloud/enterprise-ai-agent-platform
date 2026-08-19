package com.enterprise.ai.agent.config;

import com.enterprise.ai.agent.tools.ToolRegistryImpl;
import com.enterprise.ai.agent.tools.impl.CalculatorTool;
import com.enterprise.ai.agent.tools.impl.DocumentGeneratorTool;
import com.enterprise.ai.agent.tools.impl.DocumentReviewerTool;
import com.enterprise.ai.agent.tools.impl.FileWriterTool;
import com.enterprise.ai.agent.tools.impl.KnowledgeSearchTool;
import com.enterprise.ai.agent.tools.impl.OutlineGeneratorTool;
import com.enterprise.ai.agent.workflow.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ToolRegistryConfig {

    private final ToolRegistryImpl toolRegistry;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final CalculatorTool calculatorTool;
    private final DocumentGeneratorTool documentGeneratorTool;
    private final DocumentReviewerTool documentReviewerTool;
    private final FileWriterTool fileWriterTool;
    private final OutlineGeneratorTool outlineGeneratorTool;
    private final ChatClient chatClient;
    private final com.enterprise.ai.agent.artifact.ArtifactManager artifactManager;

    public ToolRegistryConfig(ToolRegistryImpl toolRegistry, 
                              KnowledgeSearchTool knowledgeSearchTool,
                              CalculatorTool calculatorTool,
                              DocumentGeneratorTool documentGeneratorTool,
                              DocumentReviewerTool documentReviewerTool,
                              FileWriterTool fileWriterTool,
                              OutlineGeneratorTool outlineGeneratorTool,
                              ChatClient chatClient,
                              com.enterprise.ai.agent.artifact.ArtifactManager artifactManager) {
        this.toolRegistry = toolRegistry;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.calculatorTool = calculatorTool;
        this.documentGeneratorTool = documentGeneratorTool;
        this.documentReviewerTool = documentReviewerTool;
        this.fileWriterTool = fileWriterTool;
        this.outlineGeneratorTool = outlineGeneratorTool;
        this.chatClient = chatClient;
        this.artifactManager = artifactManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void registerTools() {
        log.info("Registering tools with ToolRegistry");
        
        toolRegistry.register(knowledgeSearchTool);
        toolRegistry.register(calculatorTool);
        toolRegistry.register(documentGeneratorTool);
        toolRegistry.register(documentReviewerTool);
        toolRegistry.register(fileWriterTool);
        toolRegistry.register(outlineGeneratorTool);
        
        // Register tool schemas - ToolRegistry is the single source of truth
        registerToolSchemas();
        
        log.info("Tool registration completed");
    }
    
    private void registerToolSchemas() {
        log.info("Registering tool schemas");
        
        toolRegistry.registerToolSchema("knowledge_search", ToolSchema.builder()
                .name("knowledge_search")
                .description("Search for information from knowledge base")
                .addParameter("query", "string", true)
                .produces("knowledge")
                .build());
        
        toolRegistry.registerToolSchema("document_generator", ToolSchema.builder()
                .name("document_generator")
                .description("Generate documents, analysis, outlines, or reports")
                .addParameter("content", "string", true)
                .addParameter("documentType", "string", false)
                .addParameter("instructions", "string", false)
                .produces("document")
                .build());
        
        toolRegistry.registerToolSchema("outline_generator", ToolSchema.builder()
                .name("outline_generator")
                .description("Generate structured outlines for documents")
                .addParameter("content", "string", true)
                .addParameter("structure", "string", false)
                .produces("outline")
                .build());
        
        toolRegistry.registerToolSchema("document_reviewer", ToolSchema.builder()
                .name("document_reviewer")
                .description("Review and improve document quality")
                .addParameter("documentId", "string", true)
                .addParameter("reviewCriteria", "string", false)
                .produces("review")
                .build());
        
        toolRegistry.registerToolSchema("file_writer", ToolSchema.builder()
                .name("file_writer")
                .description("Write content to files")
                .addParameter("content", "string", true)
                .addParameter("filename", "string", true)
                .produces("file")
                .build());
        
        toolRegistry.registerToolSchema("calculator", ToolSchema.builder()
                .name("calculator")
                .description("Perform mathematical calculations")
                .addParameter("expression", "string", true)
                .produces("result")
                .build());
        
        log.info("Tool schema registration completed");
    }
}
