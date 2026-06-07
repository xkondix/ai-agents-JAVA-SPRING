package com.xkondix.codemcp.approval;

import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
public class ApprovalRequest {
    private String id;
    private ApprovalType type;
    private String toolName;
    private String description;
    private String details;
    private Instant createdAt;
    private ApprovalStatus status;
}
