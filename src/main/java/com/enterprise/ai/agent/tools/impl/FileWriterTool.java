package com.enterprise.ai.agent.tools.impl;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.Observation;
import com.enterprise.ai.agent.model.ToolRequest;
import com.enterprise.ai.agent.model.ToolResult;
import com.enterprise.ai.agent.tools.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class FileWriterTool implements Tool {

    private final ArtifactManager artifactManager;

    public FileWriterTool(ArtifactManager artifactManager) {
        this.artifactManager = artifactManager;
    }

    @Override
    public String name() {
        return "file_writer";
    }

    @Override
    public String description() {
        return "Write content to a file in the filesystem";
    }

    @Override
    public ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager) {
        long startTime = System.currentTimeMillis();
        log.info("Executing file_writer tool with request: {}", request);

        try {
            // Accept multiple parameter names for flexibility
            String content = (String) request.getParameters().get("content");
            String filename = (String) request.getParameters().get("filename");
            if (filename == null || filename.trim().isEmpty()) {
                filename = (String) request.getParameters().get("file");
            }
            String directory = (String) request.getParameters().getOrDefault("directory", "./output");

            // Fallback: generate filename from current milestone if not provided
            if (filename == null || filename.trim().isEmpty()) {
                String milestone = context.getCurrentMilestone();
                if (milestone != null && !milestone.isEmpty()) {
                    filename = milestone.toLowerCase().replaceAll("\\s+", "_") + ".md";
                } else {
                    filename = "output.md";
                }
                log.warn("No filename parameter found, using generated: {}", filename);
            }

            // Fallback: use observations as content if not provided
            if (content == null || content.trim().isEmpty()) {
                if (!context.getObservations().isEmpty()) {
                    content = String.join("\n", context.getObservations().stream()
                            .map(Observation::getContent)
                            .toArray(String[]::new));
                    log.warn("No content parameter found, using observations as content");
                } else {
                    content = "No content provided";
                    log.warn("No content parameter found and no observations available");
                }
            }

            if (content == null || content.trim().isEmpty()) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .result("Content parameter is required")
                        .errorMessage("Content parameter is required")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Create directory if it doesn't exist
            Path dirPath = Paths.get(directory);
            if (!Files.exists(dirPath)) {
                Files.createDirectories(dirPath);
                log.info("Created directory: {}", directory);
            }

            // Write file
            Path filePath = dirPath.resolve(filename);
            Files.writeString(filePath, content);
            
            String fullPath = filePath.toAbsolutePath().toString();
            log.info("Wrote file: {}", fullPath);

            // Create artifact record
            artifactManager.createArtifact(
                    "file",
                    filename,
                    content,
                    determineMimeType(filename),
                    "file_writer",
                    context.getExecutionId()
            );

            Map<String, Object> data = new HashMap<>();
            data.put("filename", filename);
            data.put("directory", directory);
            data.put("fullPath", fullPath);
            data.put("contentLength", content.length());

            return ToolResult.builder()
                    .toolName(name())
                    .success(true)
                    .result("File written successfully: " + fullPath)
                    .data(data)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (IOException e) {
            log.error("Error executing file_writer tool", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to write file")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("Error executing file_writer tool", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to write file")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }

    private String determineMimeType(String filename) {
        String lower = filename.toLowerCase();
        if (lower.endsWith(".md")) {
            return "text/markdown";
        } else if (lower.endsWith(".txt")) {
            return "text/plain";
        } else if (lower.endsWith(".json")) {
            return "application/json";
        } else if (lower.endsWith(".html")) {
            return "text/html";
        } else {
            return "text/plain";
        }
    }
}
