package com.xkondix.patterns.springai.patterns;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PATTERN 5 — Orchestrator-workers.
 *
 * A central LLM PLANS at runtime: decomposes the task into sub-tasks that
 * could not be hardcoded, delegates each to a worker, then synthesizes.
 * Difference vs parallelization: there the branches are known upfront
 * (you wrote them); here the orchestrator invents them per request.
 *
 * Spring AI implementation: three explicit phases in plain Java —
 * plan (structured output), dispatch (worker with tools per sub-task),
 * synthesize (final call).
 *
 * Trace signature: irregular — a "chat" (plan), then a series of tool/chat
 * spans in an order you did NOT know before running, then a final "chat".
 * Every run may produce a different shape — that IS the fingerprint.
 */
@Slf4j
@Service
public class OrchestratorWorkersPattern {

    public record Plan(List<String> subtasks) {}

    private final ChatClient plainAgent;
    private final ChatClient milanAgent;

    public OrchestratorWorkersPattern(@Qualifier("plainAgent") ChatClient plainAgent,
                                      @Qualifier("milanAgent") ChatClient milanAgent) {
        this.plainAgent = plainAgent;
        this.milanAgent = milanAgent;
    }

    public String run(String task) {
        // Phase 1 — the orchestrator decomposes the task (typed plan)
        Plan plan = plainAgent.prompt()
                .user("Decompose this AC Milan analysis task into 2-4 concrete, "
                        + "independent data-gathering subtasks (each answerable with "
                        + "squad/transfer/stats/rumor data): " + task)
                .call()
                .entity(Plan.class);
        log.info("[ORCHESTRATOR] plan: {}", plan.subtasks());

        // Phase 2 — workers execute sub-tasks (tools attached)
        String workerResults = plan.subtasks().stream()
                .map(subtask -> {
                    log.info("[ORCHESTRATOR] worker -> {}", subtask);
                    String result = milanAgent.prompt()
                            .user(subtask)
                            .call()
                            .content();
                    return "- " + subtask + "\n  " + result;
                })
                .collect(Collectors.joining("\n"));

        // Phase 3 — synthesis
        return plainAgent.prompt()
                .user("Synthesize a final answer to the task: '" + task
                        + "' from these worker results:\n" + workerResults)
                .call()
                .content();
    }
}
