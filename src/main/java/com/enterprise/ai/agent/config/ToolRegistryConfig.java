package com.enterprise.ai.agent.config;

import com.enterprise.ai.agent.tools.Tool;
import com.enterprise.ai.agent.tools.ToolRegistry;
import com.enterprise.ai.agent.tools.impl.CalculatorTool;
import com.enterprise.ai.agent.tools.impl.DocumentGeneratorTool;
import com.enterprise.ai.agent.tools.impl.FileWriterTool;
import com.enterprise.ai.agent.tools.impl.KnowledgeSearchTool;
import com.enterprise.ai.agent.tools.impl.OutlineGeneratorTool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ToolRegistryConfig {

    private final ToolRegistry toolRegistry;
    private final KnowledgeSearchTool knowledgeSearchTool;
    private final CalculatorTool calculatorTool;
    private final DocumentGeneratorTool documentGeneratorTool;
    private final FileWriterTool fileWriterTool;
    private final OutlineGeneratorTool outlineGeneratorTool;
    private final ChatClient chatClient;
    private final com.enterprise.ai.agent.artifact.ArtifactManager artifactManager;

    public ToolRegistryConfig(ToolRegistry toolRegistry, 
                              KnowledgeSearchTool knowledgeSearchTool,
                              CalculatorTool calculatorTool,
                              DocumentGeneratorTool documentGeneratorTool,
                              FileWriterTool fileWriterTool,
                              OutlineGeneratorTool outlineGeneratorTool,
                              ChatClient chatClient,
                              com.enterprise.ai.agent.artifact.ArtifactManager artifactManager) {
        this.toolRegistry = toolRegistry;
        this.knowledgeSearchTool = knowledgeSearchTool;
        this.calculatorTool = calculatorTool;
        this.documentGeneratorTool = documentGeneratorTool;
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
        toolRegistry.register(fileWriterTool);
        toolRegistry.register(outlineGeneratorTool);
        
        log.info("Tool registration completed");
    }
}
