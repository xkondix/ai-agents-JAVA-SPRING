package com.xkondix.common.dto;

import lombok.Builder;
import java.time.Instant;

/** Shared chat response DTO. */
@Builder
public record ChatResponse(
        String content,
        String model,
        Long inputTokens,
        Long outputTokens,
        Instant timestamp
) {}
