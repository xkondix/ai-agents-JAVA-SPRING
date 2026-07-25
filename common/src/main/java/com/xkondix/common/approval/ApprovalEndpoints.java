package com.xkondix.common.approval;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * REST surface of the shared approval gate — mirrors the mcp-server API
 * (GET /approvals, POST /approvals/{id}/approve|reject) so the Chat UI can
 * talk to every source the same way.
 *
 * Registered EXPLICITLY as a bean (see ApprovalConfig in the patterns
 * modules), never component-scanned or auto-configured: mcp-server already
 * maps /approvals with its own controller and two controllers claiming the
 * same path would break its startup with an ambiguous-mapping error.
 *
 * No @CrossOrigin here — the Chat UI reaches these endpoints through the
 * Vite dev proxy (/api/patterns-lc4j, /api/patterns-spring), so requests
 * are same-origin from the browser's point of view.
 */
@RestController
@RequestMapping("/approvals")
@RequiredArgsConstructor
public class ApprovalEndpoints {

    private final HumanApprovalService approvalService;

    /** Pending requests — polled by the UI every few seconds. */
    @GetMapping
    public List<PendingApproval> pending() {
        return approvalService.getPending();
    }

    /** Approve — unblocks the waiting tool call, which then executes. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable(name = "id") String id) {
        return approvalService.approve(id)
                ? ResponseEntity.ok("APPROVED " + id)
                : ResponseEntity.notFound().build();
    }

    /** Reject — unblocks the waiting tool call, which returns a refusal. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<String> reject(@PathVariable(name = "id") String id) {
        return approvalService.reject(id)
                ? ResponseEntity.ok("REJECTED " + id)
                : ResponseEntity.notFound().build();
    }
}
