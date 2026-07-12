package com.xkondix.mcpserver.approval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Human-in-the-loop approval service.
 *
 * When a sensitive tool is invoked, it calls requestApproval() which:
 *   1. registers a pending ApprovalRequest
 *   2. blocks on a CompletableFuture until a human decides
 *   3. returns true (approved) or false (rejected/timeout)
 *
 * The decision arrives over HTTP — ApprovalController calls approve()/reject(),
 * which completes the future and unblocks the waiting tool thread.
 *
 * Why this works here (HTTP) but not in a STDIO server:
 *   This server runs as a normal Spring Boot web app (SYNC_HTTP_SSE), so the
 *   agent's tool call arrives on a Tomcat thread, and the approve/reject call
 *   arrives on another Tomcat thread. With virtual threads enabled, blocking on
 *   future.get() never exhausts the pool, so concurrent approve/reject works.
 *
 * Interruption: on virtual threads, interrupt is the standard cancellation
 * mechanism (e.g. server shutdown), so InterruptedException is handled
 * separately and the interrupt flag is restored — swallowing it would hide
 * the cancellation from the rest of the call stack.
 *
 * NOTE: state is kept in-memory (ConcurrentHashMap) — pending approvals are
 * lost on restart. Fine for a demo; use Redis or a database in production.
 */
@Slf4j
@Service
public class ApprovalService {

    private final Map<String, ApprovalRequest> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> futures = new ConcurrentHashMap<>();

    private static final long TIMEOUT_MINUTES = 10;

    /**
     * Register a pending approval and block until a human decides.
     * @return true if approved, false if rejected, timed out or interrupted
     */
    public boolean requestApproval(ApprovalType type, String toolName,
                                   String description, String details) {
        String id = UUID.randomUUID().toString().substring(0, 8);

        ApprovalRequest request = ApprovalRequest.builder()
                .id(id)
                .type(type)
                .toolName(toolName)
                .description(description)
                .details(details)
                .createdAt(Instant.now())
                .status(ApprovalStatus.PENDING)
                .build();

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pending.put(id, request);
        futures.put(id, future);

        log.warn("[APPROVAL REQUIRED] id={} tool={} — {}", id, toolName, description);
        log.warn("[APPROVAL] Approve: POST http://localhost:8081/approvals/{}/approve", id);
        log.warn("[APPROVAL] Or use Chat UI: http://localhost:3000/approvals");

        try {
            // Block this (virtual) thread until a decision arrives or timeout
            return future.get(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("[APPROVAL] id={} timed out after {} min", id, TIMEOUT_MINUTES);
            request.setStatus(ApprovalStatus.TIMEOUT);
            return false;
        } catch (InterruptedException e) {
            // Restore the interrupt flag — callers (and the JDK) rely on it
            Thread.currentThread().interrupt();
            log.warn("[APPROVAL] id={} interrupted while waiting", id);
            request.setStatus(ApprovalStatus.TIMEOUT);
            return false;
        } catch (ExecutionException e) {
            // Cannot happen today (futures are only completed with a value),
            // but handled explicitly instead of a catch-all
            log.error("[APPROVAL] id={} failed: {}", id, e.getMessage());
            request.setStatus(ApprovalStatus.TIMEOUT);
            return false;
        } finally {
            pending.remove(id);
            futures.remove(id);
        }
    }

    /** Approve a pending request. Returns false if the id is unknown. */
    public boolean approve(String id) {
        CompletableFuture<Boolean> f = futures.get(id);
        if (f == null) return false;
        ApprovalRequest req = pending.get(id);
        if (req != null) req.setStatus(ApprovalStatus.APPROVED);
        f.complete(true);
        log.info("[APPROVAL] id={} APPROVED", id);
        return true;
    }

    /** Reject a pending request. Returns false if the id is unknown. */
    public boolean reject(String id) {
        CompletableFuture<Boolean> f = futures.get(id);
        if (f == null) return false;
        ApprovalRequest req = pending.get(id);
        if (req != null) req.setStatus(ApprovalStatus.REJECTED);
        f.complete(false);
        log.info("[APPROVAL] id={} REJECTED", id);
        return true;
    }

    /** List all currently pending requests. */
    public List<ApprovalRequest> getPending() {
        return new ArrayList<>(pending.values());
    }
}
