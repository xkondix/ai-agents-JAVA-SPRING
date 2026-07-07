package com.xkondix.codemcp.approval;

import com.xkondix.codemcp.config.CodeMcpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/**
 * Human-in-the-loop Approval Flow.
 * Blocks execution of sensitive operations until approved by a human.
 * Approval via REST API: POST /approvals/{id}/approve or /reject
 *
 * When the agent wants to perform a sensitive operation:
 *   1. Creates an ApprovalRequest and adds it to the pending queue
 *   2. Blocks the tool execution (CompletableFuture)
 *   3. Waits for confirmation via REST API or Chat UI /approvals page
 *   4. Resumes or cancels based on the human decision
 *
 * Timeout: configured via code-mcp.approval-timeout-minutes (default: 10)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApprovalService {

    private final CodeMcpProperties props;
    private final Map<String, ApprovalRequest> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> futures = new ConcurrentHashMap<>();

    public boolean requestApproval(ApprovalType type, String toolName,
                                   String description, String details) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        ApprovalRequest req = ApprovalRequest.builder()
                .id(id).type(type).toolName(toolName)
                .description(description).details(details)
                .createdAt(Instant.now()).status(ApprovalStatus.PENDING)
                .build();

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(id, req);
        futures.put(id, future);

        log.warn("[APPROVAL REQUIRED] id={} tool={} | {}", id, toolName, description);
        log.warn("[APPROVAL] Approve at: POST http://localhost:8086/approvals/{}/approve", id);
        log.warn("[APPROVAL] Or visit:   http://localhost:3000/approvals");

        try {
            Boolean result = future.get(props.getApprovalTimeoutMinutes(), TimeUnit.MINUTES);
            req.setStatus(result ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
            log.info("[APPROVAL] id={} status={}", id, req.getStatus());
            return Boolean.TRUE.equals(result);
        } catch (TimeoutException e) {
            req.setStatus(ApprovalStatus.TIMEOUT);
            log.warn("[APPROVAL TIMEOUT] id={} after {} minutes",
                    id, props.getApprovalTimeoutMinutes());
            return false;
        } catch (Exception e) {
            log.error("[APPROVAL ERROR] id={}", id, e);
            return false;
        } finally {
            pending.remove(id);
            futures.remove(id);
        }
    }

    public boolean approve(String id) {
        CompletableFuture<Boolean> f = futures.get(id);
        if (f == null) return false;
        f.complete(true);
        return true;
    }

    public boolean reject(String id) {
        CompletableFuture<Boolean> f = futures.get(id);
        if (f == null) return false;
        f.complete(false);
        return true;
    }

    public List<ApprovalRequest> getPending() {
        return new ArrayList<>(pending.values());
    }
}
