-- execution_contexts table for persisting execution context
CREATE TABLE execution_contexts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    execution_id UUID NOT NULL UNIQUE,
    goal TEXT NOT NULL,
    current_step INT NOT NULL DEFAULT 0,
    current_milestone TEXT,
    completed_milestones TEXT,
    observations TEXT,
    tool_results TEXT,
    artifacts TEXT,
    reviews TEXT,
    failures TEXT,
    outputs TEXT,
    variables TEXT,
    knowledge_references TEXT,
    retry_history TEXT,
    metadata TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP,
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE'
);

-- artifacts table for persisting generated artifacts
CREATE TABLE artifacts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    artifact_id UUID NOT NULL UNIQUE,
    version INT NOT NULL DEFAULT 1,
    parent_artifact_id UUID,
    type VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    mime_type VARCHAR(100) NOT NULL,
    created_by VARCHAR(100) NOT NULL,
    related_execution_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- Create indexes for better query performance
CREATE INDEX idx_execution_contexts_execution_id ON execution_contexts(execution_id);
CREATE INDEX idx_execution_contexts_status ON execution_contexts(status);
CREATE INDEX idx_artifacts_artifact_id ON artifacts(artifact_id);
CREATE INDEX idx_artifacts_related_execution_id ON artifacts(related_execution_id);
CREATE INDEX idx_artifacts_type ON artifacts(type);
CREATE INDEX idx_artifacts_parent_artifact_id ON artifacts(parent_artifact_id);
CREATE INDEX idx_artifacts_version ON artifacts(version);
