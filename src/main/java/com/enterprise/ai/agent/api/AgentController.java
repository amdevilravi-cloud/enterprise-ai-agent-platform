package com.enterprise.ai.agent.api;

import com.enterprise.ai.agent.agent.Agent;
import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.agent_runtime.ExecutionContextManager;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.config.CorrelationIdUtil;
import com.enterprise.ai.agent.model.*;
import com.enterprise.ai.agent.planner.Planner;
import com.enterprise.ai.agent.tools.Tool;
import com.enterprise.ai.agent.tools.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/agent")
@Slf4j
@Tag(name = "Agent API", description = "Agent execution and orchestration endpoints")
public class AgentController {

    private final Agent agent;
    private final Planner planner;
    private final ToolRegistry toolRegistry;
    private final ExecutionContextManager contextManager;
    private final ArtifactManager artifactManager;

    public AgentController(Agent agent, Planner planner, ToolRegistry toolRegistry, 
                          ExecutionContextManager contextManager, ArtifactManager artifactManager) {
        this.agent = agent;
        this.planner = planner;
        this.toolRegistry = toolRegistry;
        this.contextManager = contextManager;
        this.artifactManager = artifactManager;
    }

    @PostMapping("/execute")
    @Operation(summary = "Execute agent request", description = "Execute an agent request with the given goal")
    public ResponseEntity<AgentResponse> executeAgent(@Valid @RequestBody AgentRequest request) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Received agent execution request for goal: {}", correlationId, request.getGoal());
        AgentResponse response = agent.execute(request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/plan")
    @Operation(summary = "Generate plan", description = "Generate a plan without executing it")
    public ResponseEntity<PlanningResult> generatePlan(@Valid @RequestBody AgentRequest request) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Received plan generation request for goal: {}", correlationId, request.getGoal());
        ExecutionContext context = ExecutionContext.create(request.getGoal());
        PlanningResult plan = planner.createPlan(context);
        contextManager.discardContext(context.getExecutionId());
        return ResponseEntity.ok(plan);
    }

    @PostMapping("/tool")
    @Operation(summary = "Execute single tool", description = "Execute a single tool with the given parameters")
    public ResponseEntity<ToolResult> executeTool(@Valid @RequestBody ToolRequest request) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Received tool execution request for tool: {}", correlationId, request.getToolName());
        Tool tool = toolRegistry.get(request.getToolName());
        if (tool == null) {
            return ResponseEntity.badRequest().build();
        }
        ExecutionContext context = ExecutionContext.create("Single tool execution");
        ToolResult result = tool.execute(request, context, artifactManager);
        contextManager.discardContext(context.getExecutionId());
        return ResponseEntity.ok(result);
    }

    @GetMapping("/status")
    @Operation(summary = "Get agent status", description = "Get the current status of the agent platform")
    public ResponseEntity<Map<String, Object>> getAgentStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("activeContexts", contextManager.getActiveContextCount());
        status.put("registeredTools", toolRegistry instanceof com.enterprise.ai.agent.tools.ToolRegistryImpl 
            ? ((com.enterprise.ai.agent.tools.ToolRegistryImpl) toolRegistry).getToolCount() 
            : 0);
        status.put("status", "running");
        return ResponseEntity.ok(status);
    }

    @GetMapping("/tools")
    @Operation(summary = "List available tools", description = "Get a list of all available tools")
    public ResponseEntity<Map<String, String>> getAvailableTools() {
        Map<String, String> tools = new HashMap<>();
        // This is a simplified version. In production, you'd iterate over registered tools
        tools.put("knowledge_search", "Search the knowledge base for information");
        tools.put("calculator", "Perform mathematical calculations");
        tools.put("document_generator", "Generate documents using LLM");
        tools.put("outline_generator", "Generate structured outlines");
        tools.put("file_writer", "Write content to filesystem");
        return ResponseEntity.ok(tools);
    }

    @GetMapping("/artifacts/{executionId}")
    @Operation(summary = "List artifacts for execution", description = "Get all artifacts generated during an execution")
    public ResponseEntity<List<Artifact>> getArtifactsForExecution(@PathVariable UUID executionId) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Retrieving artifacts for execution: {}", correlationId, executionId);
        List<Artifact> artifacts = artifactManager.listArtifacts(executionId);
        return ResponseEntity.ok(artifacts);
    }

    @GetMapping("/artifact/{artifactId}")
    @Operation(summary = "Get artifact by ID", description = "Get a specific artifact by its ID")
    public ResponseEntity<Artifact> getArtifact(@PathVariable UUID artifactId) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Retrieving artifact: {}", correlationId, artifactId);
        Artifact artifact = artifactManager.getArtifact(artifactId);
        if (artifact == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(artifact);
    }
    
    @GetMapping("/artifact/{artifactId}/versions")
    @Operation(summary = "Get artifact versions", description = "Get all versions of an artifact")
    public ResponseEntity<List<Artifact>> getArtifactVersions(@PathVariable UUID artifactId) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Retrieving versions for artifact: {}", correlationId, artifactId);
        List<Artifact> versions = artifactManager.getArtifactVersions(artifactId);
        return ResponseEntity.ok(versions);
    }
    
    @PostMapping("/artifact/{artifactId}/rollback/{version}")
    @Operation(summary = "Rollback artifact", description = "Rollback an artifact to a specific version")
    public ResponseEntity<Artifact> rollbackArtifact(@PathVariable UUID artifactId, @PathVariable int version) {
        String correlationId = CorrelationIdUtil.getCorrelationId();
        log.info("[{}] Rolling back artifact {} to version {}", correlationId, artifactId, version);
        Artifact rolledBack = artifactManager.rollbackArtifact(artifactId, version);
        if (rolledBack == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(rolledBack);
    }
}
