package com.enterprise.ai.agent.tools.impl;

import com.enterprise.ai.agent.agent_runtime.ExecutionContext;
import com.enterprise.ai.agent.artifact.ArtifactManager;
import com.enterprise.ai.agent.model.ToolRequest;
import com.enterprise.ai.agent.model.ToolResult;
import com.enterprise.ai.agent.tools.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class CalculatorTool implements Tool {

    private final ScriptEngine engine;

    public CalculatorTool() {
        ScriptEngineManager manager = new ScriptEngineManager();
        this.engine = manager.getEngineByName("js");
    }

    @Override
    public String name() {
        return "calculator";
    }

    @Override
    public String description() {
        return "Perform mathematical calculations. Supports basic arithmetic operations.";
    }

    @Override
    public ToolResult execute(ToolRequest request, ExecutionContext context, ArtifactManager artifactManager) {
        long startTime = System.currentTimeMillis();
        log.info("Executing calculator tool with request: {}", request);

        try {
            String expression = (String) request.getParameters().get("expression");
            if (expression == null || expression.trim().isEmpty()) {
                return ToolResult.builder()
                        .toolName(name())
                        .success(false)
                        .result("Expression parameter is required")
                        .errorMessage("Expression parameter is required")
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }

            // Evaluate the expression
            Object result = engine.eval(expression);
            
            Map<String, Object> data = new HashMap<>();
            data.put("expression", expression);
            data.put("result", result);

            // Store result in context
            context.setVariable("calculation_result", result);
            context.setVariable("last_expression", expression);

            return ToolResult.builder()
                    .toolName(name())
                    .success(true)
                    .result(result.toString())
                    .data(data)
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (ScriptException e) {
            log.error("Error evaluating expression", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to evaluate expression")
                    .errorMessage("Invalid expression: " + e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        } catch (Exception e) {
            log.error("Error executing calculator tool", e);
            return ToolResult.builder()
                    .toolName(name())
                    .success(false)
                    .result("Failed to perform calculation")
                    .errorMessage(e.getMessage())
                    .durationMs(System.currentTimeMillis() - startTime)
                    .build();
        }
    }
}
