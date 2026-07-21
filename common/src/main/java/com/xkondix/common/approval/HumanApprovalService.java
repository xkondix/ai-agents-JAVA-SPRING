package com.xkondix.common.approval;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Reusable human-in-the-loop gate.
 *
 * Used by mcp-server (save_note / delete_note over MCP) and by both
 * patterns modules (getSecretRumors). NOT used by code-mcp-server: it runs
 * over STDIO, where no HTTP endpoint can unblock a waiting tool call, so
 * approvals are disabled there by design.
 *
 * Two APIs:
 *   gate(...)           — generic, preferred: wraps the guarded action so a
 *                         tool has no if/else boilerplate and cannot forget
 *                         to check the result;
 *   requestApproval(...) — low-level boolean, when you need custom control
 *                         flow around the decision.
 *
 * How it works:
 *   1. a PendingApproval is registered
 *   2. the calling thread BLOCKS on a CompletableFuture
 *   3. a human decides over HTTP → the future completes
 *   4. the guarded action runs (approved) or the fallback is returned
 *
 * Requires virtual threads (spring.threads.virtual.enabled=true) in the
 * hosting app: the call blocks for as long as the human thinks, and a
 * platform-thread Tomcat pool would be exhausted by a few pending approvals.
 *
 * NOT annotated with @Service and NOT auto-configured — every consumer
 * declares the bean explicitly, so a module can opt out entirely.
 *
 * State is in-memory: pending approvals are lost on restart. Fine for a
 * demo; production would use Redis or a database.
 */
@Slf4j
public class HumanApprovalService {

    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> futures = new ConcurrentHashMap<>();

    private final long timeoutMinutes;
    private final String uiHint;

    public HumanApprovalService(long timeoutMinutes, String uiHint) {
        this.timeoutMinutes = timeoutMinutes;
        this.uiHint = uiHint;
    }

    /**
     * GENERIC GATE — runs {@code action} only after a human approves.
     *
     * The guarded work stays a lambda, so adding an approval to any
     * operation is a wrapper, not a rewrite:
     * <pre>
     * return approval.gate("DELETE_NOTE", "delete_note",
     *         "Delete note: " + id, details,
     *         () -> { notes.remove(id); return "Deleted: " + id; },
     *         () -> "REJECTED: note was not deleted.");
     * </pre>
     *
     * @param type        UI category, e.g. SAVE_NOTE / SECRET_RUMORS
     * @param toolName    tool being gated (for logs and the UI)
     * @param description one-line summary shown to the human
     * @param details     full payload shown in the expandable section
     * @param action      executed ONLY when approved
     * @param whenDenied  value returned on rejection, timeout or interrupt
     * @param <T>         result type of the guarded operation
     */
    public <T> T gate(String type, String toolName, String description, String details,
                      Supplier<T> action, Supplier<T> whenDenied) {
        boolean approved = requestApproval(type, toolName, description, details);
        if (!approved) {
            log.info("[APPROVAL] {} DENIED — running fallback", toolName);
            return whenDenied.get();
        }
        log.info("[APPROVAL] {} APPROVED — executing", toolName);
        return action.get();
    }

    /**
     * Convenience overload for the common case: a tool returning text to the
     * model, with a fixed refusal message.
     */
    public String gate(String type, String toolName, String description, String details,
                       Supplier<String> action, String deniedMessage) {
        return gate(type, toolName, description, details, action, () -> deniedMessage);
    }

    /**
     * Low-level API: registers a pending approval and blocks until a human
     * decides.
     *
     * @return true if approved; false if rejected, timed out or interrupted
     */
    public boolean requestApproval(String type, String toolName,
                                   String description, String details) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        PendingApproval request = new PendingApproval(id, type, toolName, description, details);
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        pending.put(id, request);
        futures.put(id, future);

        log.warn("[APPROVAL REQUIRED] id={} tool={} — {}", id, toolName, description);
        log.warn("[APPROVAL] Decide in the Chat UI: {}", uiHint);

        try {
            return future.get(timeoutMinutes, TimeUnit.MINUTES);
        } catch (TimeoutException e) {
            log.warn("[APPROVAL] id={} timed out after {} min", id, timeoutMinutes);
            request.setStatus("TIMEOUT");
            return false;
        } catch (InterruptedException e) {
            // On virtual threads interrupt is the standard cancellation signal —
            // restore the flag instead of swallowing it
            Thread.currentThread().interrupt();
            log.warn("[APPROVAL] id={} interrupted while waiting", id);
            request.setStatus("TIMEOUT");
            return false;
        } catch (ExecutionException e) {
            log.error("[APPROVAL] id={} failed: {}", id, e.getMessage());
            request.setStatus("TIMEOUT");
            return false;
        } finally {
            pending.remove(id);
            futures.remove(id);
        }
    }

    /** Approve a pending request. Returns false when the id is unknown. */
    public boolean approve(String id) {
        return complete(id, true, "APPROVED");
    }

    /** Reject a pending request. Returns false when the id is unknown. */
    public boolean reject(String id) {
        return complete(id, false, "REJECTED");
    }

    private boolean complete(String id, boolean decision, String status) {
        CompletableFuture<Boolean> future = futures.get(id);
        if (future == null) {
            return false;
        }
        PendingApproval request = pending.get(id);
        if (request != null) {
            request.setStatus(status);
        }
        future.complete(decision);
        log.info("[APPROVAL] id={} {}", id, status);
        return true;
    }

    /** All requests currently waiting for a human. */
    public List<PendingApproval> getPending() {
        return new ArrayList<>(pending.values());
    }
}
