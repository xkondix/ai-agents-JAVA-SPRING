package com.xkondix.codemcp.approval;

import com.fasterxml.jackson.annotation.JsonFormat;
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

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    private ApprovalStatus status;
}
