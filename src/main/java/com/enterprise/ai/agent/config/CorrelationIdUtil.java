package com.enterprise.ai.agent.config;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Utility class for managing correlation IDs throughout the application.
 * Provides methods to get, set, and generate correlation IDs.
 */
public class CorrelationIdUtil {

    private static final String CORRELATION_ID_MDC_KEY = "correlationId";

    /**
     * Get the current correlation ID from MDC.
     * @return the correlation ID or null if not set
     */
    public static String getCorrelationId() {
        return MDC.get(CORRELATION_ID_MDC_KEY);
    }

    /**
     * Set a correlation ID in MDC.
     * @param correlationId the correlation ID to set
     */
    public static void setCorrelationId(String correlationId) {
        if (correlationId != null && !correlationId.isEmpty()) {
            MDC.put(CORRELATION_ID_MDC_KEY, correlationId);
        }
    }

    /**
     * Generate a new correlation ID.
     * @return a new UUID as correlation ID
     */
    public static String generateCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Generate and set a new correlation ID in MDC.
     * @return the generated correlation ID
     */
    public static String generateAndSetCorrelationId() {
        String correlationId = generateCorrelationId();
        setCorrelationId(correlationId);
        return correlationId;
    }

    /**
     * Clear the correlation ID from MDC.
     */
    public static void clearCorrelationId() {
        MDC.remove(CORRELATION_ID_MDC_KEY);
    }

    /**
     * Execute a runnable with a specific correlation ID.
     * @param correlationId the correlation ID to use
     * @param runnable the runnable to execute
     */
    public static void withCorrelationId(String correlationId, Runnable runnable) {
        String existingId = getCorrelationId();
        try {
            setCorrelationId(correlationId);
            runnable.run();
        } finally {
            if (existingId != null) {
                setCorrelationId(existingId);
            } else {
                clearCorrelationId();
            }
        }
    }
}