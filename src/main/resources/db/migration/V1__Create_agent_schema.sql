-- agent_execution
CREATE TABLE agent_execution (
    id UUID PRIMARY KEY,
    goal TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP
);

-- execution_step
CREATE TABLE execution_step (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL,
    step_number INT NOT NULL,
    action TEXT NOT NULL,
    status VARCHAR(50) NOT NULL,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    CONSTRAINT fk_execution_step_execution FOREIGN KEY (execution_id) REFERENCES agent_execution(id)
);

-- tool_call
CREATE TABLE tool_call (
    id UUID PRIMARY KEY,
    execution_step_id UUID NOT NULL,
    tool_name VARCHAR(100) NOT NULL,
    request TEXT NOT NULL,
    response TEXT,
    duration_ms BIGINT,
    CONSTRAINT fk_tool_call_execution_step FOREIGN KEY (execution_step_id) REFERENCES execution_step(id)
);

-- agent_memory
CREATE TABLE agent_memory (
    id UUID PRIMARY KEY,
    execution_id UUID NOT NULL,
    key VARCHAR(100) NOT NULL,
    value TEXT NOT NULL,
    CONSTRAINT fk_agent_memory_execution FOREIGN KEY (execution_id) REFERENCES agent_execution(id)
);

-- workflow (placeholder for future)
CREATE TABLE workflow (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(20) NOT NULL
);

-- workflow_execution (placeholder for future)
CREATE TABLE workflow_execution (
    id UUID PRIMARY KEY,
    workflow_id UUID NOT NULL,
    execution_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    CONSTRAINT fk_workflow_execution_workflow FOREIGN KEY (workflow_id) REFERENCES workflow(id),
    CONSTRAINT fk_workflow_execution_execution FOREIGN KEY (execution_id) REFERENCES agent_execution(id)
);

-- Create indexes for better query performance
CREATE INDEX idx_execution_step_execution_id ON execution_step(execution_id);
CREATE INDEX idx_tool_call_execution_step_id ON tool_call(execution_step_id);
CREATE INDEX idx_agent_memory_execution_id ON agent_memory(execution_id);
CREATE INDEX idx_workflow_execution_execution_id ON workflow_execution(execution_id);
