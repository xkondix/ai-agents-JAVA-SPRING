package com.xkondix.common.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Builder;

/**
 * Shared chat request DTO used by both frameworks.
 */
@Builder
public record ChatRequest(
        @NotBlank String message,
        String userId,
        String conversationId
) {
}
