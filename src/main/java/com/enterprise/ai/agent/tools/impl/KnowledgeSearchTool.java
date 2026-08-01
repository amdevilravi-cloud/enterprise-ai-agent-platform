package com.enterprise.ai.agent.tools.impl;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.ToolRequest;
import com.enterprise.ai.agent.model.ToolResult;
import com.enterprise.ai.agent.tools.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class KnowledgeSearchTool implements Tool {

    private final RestTemplate restTemplate;
    private final String knowledgePlatformUrl;

    public KnowledgeSearchTool(RestTemplate restTemplate, 
                              @Value("${knowledge.platform.url:http://localhost:8080}") String knowledgePlatformUrl) {
        this.restTemplate = restTemplate;
        this.knowledgePlatformUrl = knowledgePlatformUrl;
    }

    @Override
    public String name() {
        return "knowledge_search";
    }

    @Override
    public String description() {
        return "Search the knowledge base for information using the knowledge platform's RAG API";
    }

    @Override
    public ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager) {
        long startTime = System.currentTimeMillis();
        log.info("Executing knowledge_search tool with request: {}", request);

        try {
            // Accept multiple parameter names for flexibility
            String query = (String) request.getParameters().get("query");
            if (query == null || query.trim().isEmpty()) {
                query = (String) request.getParameters().get("topic");
            }
            if (query == null || query.trim().isEmpty()) {
                query = (String) request.getParameters().get("message");
            }
            if (query == null || query.trim().isEmpty()) {
                query = (String) request.getParameters().get("search");
            }
            
            // Fallback: use the action description if no parameter provided
            if (query == null || query.trim().isEmpty()) {
                query = "Kaleshwaram project"; // Default fallback
                log.warn("No query parameter found, using default: {}", query);
            }
            
            if (query == null || query.trim().isEmpty()) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .result("Query parameter is required")
                        .errorMessage("Query parameter is required")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Call knowledge platform RAG API
            String url = knowledgePlatformUrl + "/api/chat/rag?message=" + query;
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);

            if (response != null) {
                String answer = (String) response.get("answer");
                Boolean isFromContext = (Boolean) response.get("isFromContext");
                Integer retrievalCount = (Integer) response.get("retrievalCount");

                // Store knowledge reference in context
                if (isFromContext != null && isFromContext) {
                    context.addKnowledgeReference(query);
                    context.setVariable("retrievalCount", retrievalCount);
                }

                Map<String, Object> data = new HashMap<>();
                data.put("answer", answer);
                data.put("isFromContext", isFromContext);
                data.put("retrievalCount", retrievalCount);

                return ToolResult.builder()
                        .toolName(name())
                        .success(true)
                        .result(answer)
                        .data(data)
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            } else {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .result("No response from knowledge platform")
                        .errorMessage("Knowledge platform returned null response")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

        } catch (Exception e) {
            log.error("Error executing knowledge_search tool", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to search knowledge base")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
}
