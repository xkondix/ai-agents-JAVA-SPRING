package com.xkondix.mcpserver.approval;

import com.xkondix.common.approval.HumanApprovalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Human-in-the-loop approval service for mcp-server.
 *
 * The mechanism lives in `common` (HumanApprovalService) so every module
 * shares one implementation; this class only turns it into a Spring bean for
 * this application and keeps the local type name used across the module.
 *
 * Inherited API:
 *   gate(type, tool, description, details, action, denied) — generic wrapper,
 *   requestApproval(...) / approve(id) / reject(id) / getPending().
 *
 * Why it works here but NOT in claude-mcp-server:
 *   this server is a normal web app speaking MCP over Streamable HTTP, so the
 *   agent's tool call arrives on one request thread and the approve/reject
 *   decision on another (the Approval REST API, driven from the chat-ui) —
 *   with virtual threads, blocking on the decision costs nothing. A STDIO
 *   server has no second channel to unblock the waiting call, which is why
 *   approvals stay out of claude-mcp-server by design.
 *
 * The wait is visible: McpToolTelemetry wraps the whole tool call, so a
 * gated tool shows up in Tempo as one long "mcp_tool save_note" span and on
 * the "Human-in-the-loop wait" dashboard panel.
 */
@Service
public class ApprovalService extends HumanApprovalService {

    public ApprovalService(
            @Value("${xkondix.approval.timeout-minutes:10}") long timeoutMinutes) {
        super(timeoutMinutes, "http://localhost:3000/approvals");
    }
}
