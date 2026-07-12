package com.xkondix.mcpserver.controller;

import com.xkondix.mcpserver.approval.ApprovalRequest;
import com.xkondix.mcpserver.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * REST API for the human-in-the-loop Approval Flow.
 *
 * The Chat UI (/approvals page) polls GET /approvals and posts approve/reject.
 * CORS is open so the Vite dev server (localhost:3000) can call it directly.
 */
@RestController
@RequestMapping("/approvals")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalService approvalService;

    /** List all pending approval requests. */
    @GetMapping
    public List<ApprovalRequest> getPending() {
        return approvalService.getPending();
    }

    /** Approve a pending operation. 200 if found, 404 otherwise. */
    @PostMapping("/{id}/approve")
    public ResponseEntity<String> approve(@PathVariable String id) {
        boolean ok = approvalService.approve(id);
        return ok ? ResponseEntity.ok("Approved: " + id)
                  : ResponseEntity.notFound().build();
    }

    /** Reject a pending operation. 200 if found, 404 otherwise. */
    @PostMapping("/{id}/reject")
    public ResponseEntity<String> reject(@PathVariable String id) {
        boolean ok = approvalService.reject(id);
        return ok ? ResponseEntity.ok("Rejected: " + id)
                  : ResponseEntity.notFound().build();
    }
}
