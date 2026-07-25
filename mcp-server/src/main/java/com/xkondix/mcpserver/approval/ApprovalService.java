package com.xkondix.mcpserver.approval;

import com.xkondix.common.approval.HumanApprovalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Human-in-the-loop approval service for mcp-server.
 *
 * The mechanism now lives in `common` (HumanApprovalService) so every module
 * shares one implementation; this class only turns it into a Spring bean for
 * this application and keeps the local type name used across the module.
 *
 * Inherited API:
 *   gate(type, tool, description, details, action, denied) — generic wrapper,
 *   requestApproval(...) / approve(id) / reject(id) / getPending().
 *
 * Why it works here but NOT in code-mcp-server:
 *   this server runs as a normal web app (SYNC_HTTP_SSE), so the agent's tool
 *   call arrives on one request thread and the approve/reject decision on
 *   another — with virtual threads, blocking on the decision costs nothing.
 *   A STDIO server has no HTTP endpoint to unblock the waiting call, which is
 *   why approvals stay disabled in code-mcp-server.
 */
@Service
public class ApprovalService extends HumanApprovalService {

    public ApprovalService(
            @Value("${xkondix.approval.timeout-minutes:10}") long timeoutMinutes) {
        super(timeoutMinutes, "http://localhost:3000/approvals");
    }
}
