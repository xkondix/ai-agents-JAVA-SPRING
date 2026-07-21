package com.xkondix.patterns.springai.patterns;

import com.xkondix.common.milan.MilanKnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PATTERN 3 — Parallelization (sectioning).
 *
 * Independent sub-tasks run concurrently; CODE aggregates the results.
 * The branches are known upfront (unlike orchestrator-workers, where the
 * model invents them at runtime).
 *
 * Spring AI implementation: CompletableFuture + virtual threads — again,
 * plain Java. Latency drops from sum(branches) to max(branch).
 *
 * Trace signature: OVERLAPPING "chat" spans with a common start — the only
 * pattern where the waterfall stops being a staircase.
 *
 * NOTE: this pattern is why we migrated to OpenRouter — a single local
 * Ollama serializes inferences and would demo the queue, not the pattern.
 *
 * Demo: score every rumored transfer candidate concurrently, pick the best.
 */
@Slf4j
@Service
public class ParallelizationPattern {

    public record CandidateScore(String player, double score, String rationale) {}

    private final ChatClient plainAgent;
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public ParallelizationPattern(@Qualifier("plainAgent") ChatClient plainAgent) {
        this.plainAgent = plainAgent;
    }

    public String run() {
        var candidates = MilanKnowledgeBase.secretRumors();
        log.info("[PARALLEL] fan-out over {} candidates", candidates.size());

        // Fan-out — one LLM call per candidate, all at once
        List<CompletableFuture<CandidateScore>> futures = candidates.stream()
                .map(rumor -> CompletableFuture.supplyAsync(() -> plainAgent.prompt()
                        .user("Score this transfer candidate for AC Milan from 0.0 to 1.0 "
                                + "considering probability and squad needs. Candidate: " + rumor)
                        .call()
                        .entity(CandidateScore.class), executor))
                .toList();

        // Barrier + aggregation in CODE (could also be a final LLM call)
        List<CandidateScore> scores = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        scores.forEach(s -> log.info("[PARALLEL] {} -> {}", s.player(), s.score()));

        CandidateScore best = scores.stream()
                .max(Comparator.comparingDouble(CandidateScore::score))
                .orElseThrow();

        return "Best candidate: " + best.player()
                + " (score " + best.score() + ") — " + best.rationale()
                + "\n\nAll scores: " + scores;
    }
}
