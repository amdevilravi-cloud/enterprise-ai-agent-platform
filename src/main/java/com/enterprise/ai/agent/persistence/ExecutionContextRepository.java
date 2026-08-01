package com.enterprise.ai.agent.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * ExecutionContextRepository - JPA repository for ExecutionContextEntity.
 * Provides database operations for execution context persistence.
 */
@Repository
public interface ExecutionContextRepository extends JpaRepository<ExecutionContextEntity, UUID> {
    
    /**
     * Find execution context by execution ID
     */
    Optional<ExecutionContextEntity> findByExecutionId(UUID executionId);
    
    /**
     * Delete execution context by execution ID
     */
    void deleteByExecutionId(UUID executionId);
}
