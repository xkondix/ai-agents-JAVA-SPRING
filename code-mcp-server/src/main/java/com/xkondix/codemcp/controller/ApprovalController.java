package com.xkondix.codemcp.controller;

import com.xkondix.codemcp.approval.ApprovalRequest;
import com.xkondix.codemcp.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/approvals")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class ApprovalController {

    private final ApprovalService approvalService;

    @GetMapping
    public ResponseEntity<List<ApprovalRequest>> getPending() {
        return ResponseEntity.ok(approvalService.getPending());
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Map<String, Object>> approve(@PathVariable String id) {
        boolean ok = approvalService.approve(id);
        return ok
                ? ResponseEntity.ok(Map.of("status", "approved", "id", id))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<Map<String, Object>> reject(@PathVariable String id) {
        boolean ok = approvalService.reject(id);
        return ok
                ? ResponseEntity.ok(Map.of("status", "rejected", "id", id))
                : ResponseEntity.notFound().build();
    }
}
