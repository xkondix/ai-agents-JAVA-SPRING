package com.xkondix.mcpserver.approval;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;

/**
 * A pending approval request, surfaced to the Chat UI via REST.
 *
 * createdAt uses @JsonFormat so Jackson serializes Instant as an ISO string
 * instead of failing (no JavaTimeModule needed for this field).
 */
@Data
@Builder
public class ApprovalRequest {
    private String id;
    private ApprovalType type;
    private String toolName;
    private String description;
    private String details;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    private ApprovalStatus status;
}
