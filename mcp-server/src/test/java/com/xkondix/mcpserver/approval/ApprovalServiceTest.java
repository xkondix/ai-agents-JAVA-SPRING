package com.xkondix.mcpserver.approval;

import com.xkondix.common.approval.PendingApproval;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for the human-in-the-loop approval flow.
 *
 * requestApproval() blocks the calling thread, so each test runs it on a
 * virtual thread (same as production, where Tomcat uses virtual threads)
 * and drives the decision from the test thread — exactly like the real
 * flow where the tool call and the approve/reject arrive on different
 * threads.
 *
 * The mechanism now lives in common (HumanApprovalService); ApprovalService
 * is the Spring bean for this module, so these tests cover both. Types are
 * plain Strings and PendingApproval instead of the module-local enums —
 * one payload shape for every source the Chat UI polls.
 */
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ApprovalServiceTest {

    /** Timeout in minutes — long enough that it never fires during a test. */
    private static final long TIMEOUT_MINUTES = 5;

    private ApprovalService service;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        service = new ApprovalService(TIMEOUT_MINUTES);
        executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Future<Boolean> submitRequest() {
        return executor.submit(() -> service.requestApproval(
                "SAVE_NOTE", "save_note",
                "Save a note titled 'demo'", "note content"));
    }

    /** Poll until the request becomes visible as pending. */
    private String awaitPendingId() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            var pending = service.getPending();
            if (!pending.isEmpty()) {
                return pending.get(0).getId();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Request never appeared in pending list");
    }

    // ── Decision paths ────────────────────────────────────────────────────

    @Test
    void approveUnblocksWaitingToolWithTrue() throws Exception {
        Future<Boolean> result = submitRequest();
        String id = awaitPendingId();

        boolean found = service.approve(id);

        assertThat(found).isTrue();
        assertThat(result.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(service.getPending()).isEmpty();
    }

    @Test
    void rejectUnblocksWaitingToolWithFalse() throws Exception {
        Future<Boolean> result = submitRequest();
        String id = awaitPendingId();

        boolean found = service.reject(id);

        assertThat(found).isTrue();
        assertThat(result.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(service.getPending()).isEmpty();
    }

    @Test
    void pendingRequestIsVisibleWhileToolIsBlocked() throws Exception {
        Future<Boolean> result = submitRequest();
        String id = awaitPendingId();

        var pending = service.getPending();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getToolName()).isEqualTo("save_note");
        assertThat(pending.get(0).getType()).isEqualTo("SAVE_NOTE");
        assertThat(pending.get(0).getStatus()).isEqualTo("PENDING");

        service.approve(id); // cleanup — unblock the waiting thread
        result.get(2, TimeUnit.SECONDS);
    }

    // ── Generic gate ──────────────────────────────────────────────────────

    @Test
    void gateRunsGuardedAction_onlyAfterApproval() throws Exception {
        Future<String> result = executor.submit(() -> service.gate(
                "SAVE_NOTE", "save_note",
                "Save a note titled 'demo'", "note content",
                () -> "EXECUTED",
                "REJECTED: not saved."));

        String id = awaitPendingId();
        service.approve(id);

        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("EXECUTED");
    }

    @Test
    void gateSkipsGuardedAction_whenRejected() throws Exception {
        // The action must NOT run — a flag proves the lambda was never called
        boolean[] executed = { false };

        Future<String> result = executor.submit(() -> service.gate(
                "DELETE_NOTE", "delete_note",
                "Delete note 'demo'", "id=demo",
                () -> { executed[0] = true; return "EXECUTED"; },
                "REJECTED: not deleted."));

        String id = awaitPendingId();
        service.reject(id);

        assertThat(result.get(2, TimeUnit.SECONDS)).isEqualTo("REJECTED: not deleted.");
        assertThat(executed[0]).isFalse();
    }

    // ── Unknown ids ───────────────────────────────────────────────────────

    @Test
    void approveReturnsFalse_forUnknownId() {
        assertThat(service.approve("nope1234")).isFalse();
    }

    @Test
    void rejectReturnsFalse_forUnknownId() {
        assertThat(service.reject("nope1234")).isFalse();
    }

    @Test
    void decisionOnAlreadyResolvedRequest_returnsFalse() throws Exception {
        Future<Boolean> result = submitRequest();
        String id = awaitPendingId();

        service.approve(id);
        result.get(2, TimeUnit.SECONDS);

        // The id was removed in the finally block — a second decision
        // (e.g. a double click in the UI) must be a no-op.
        assertThat(service.approve(id)).isFalse();
        assertThat(service.reject(id)).isFalse();
    }

    // ── Concurrency ───────────────────────────────────────────────────────

    @Test
    void multipleConcurrentRequests_resolveIndependently() throws Exception {
        Future<Boolean> first = submitRequest();
        String firstId = awaitPendingId();

        Future<Boolean> second = executor.submit(() -> service.requestApproval(
                "DELETE_NOTE", "delete_note",
                "Delete note 'demo'", "id=demo"));

        long deadline = System.currentTimeMillis() + 5_000;
        while (service.getPending().size() < 2
                && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertThat(service.getPending()).hasSize(2);

        String secondId = service.getPending().stream()
                .map(PendingApproval::getId)
                .filter(id -> !id.equals(firstId))
                .findFirst()
                .orElseThrow();

        service.approve(firstId);
        service.reject(secondId);

        assertThat(first.get(2, TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(service.getPending()).isEmpty();
    }
}
