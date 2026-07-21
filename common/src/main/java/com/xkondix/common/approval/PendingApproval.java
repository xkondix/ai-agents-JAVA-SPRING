package com.xkondix.common.approval;

import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * One human-in-the-loop approval request.
 *
 * Field names are intentionally identical to the mcp-server variant so the
 * existing Chat UI (chat-ui/src/pages/ApprovalsPage.jsx) renders requests
 * from any source without changes: id, type, description, details,
 * createdAt, status.
 *
 * `source` is added on the CLIENT side after fetching (the UI needs to know
 * where to send approve/reject), so it is not part of this payload.
 */
@Getter
public class PendingApproval {

    private final String id;
    /** UI keys its icon/colour config off this, e.g. SECRET_RUMORS. */
    private final String type;
    private final String toolName;
    private final String description;
    private final String details;
    private final Instant createdAt;

    /** PENDING → APPROVED / REJECTED / TIMEOUT */
    @Setter
    private volatile String status;

    public PendingApproval(String id, String type, String toolName,
                           String description, String details) {
        this.id = id;
        this.type = type;
        this.toolName = toolName;
        this.description = description;
        this.details = details;
        this.createdAt = Instant.now();
        this.status = "PENDING";
    }
}
