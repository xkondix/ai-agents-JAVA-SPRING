package com.xkondix.common.model;

import lombok.Builder;
import java.time.Instant;

/**
 * Immutable record of a single tool invocation.
 * Used for observability across both frameworks.
 */
@Builder
public record ToolCallLog(
        String toolName,
        String arguments,
        String result,
        long durationMs,
        boolean success,
        String errorMessage,
        Instant calledAt
) {}
