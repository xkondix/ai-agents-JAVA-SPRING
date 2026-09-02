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
 *
 * ── STRUCTURED OUTPUT IS BEST-EFFORT; THE CODE HAS TO SAY SO ────────────────
 *
 * BeanOutputConverter puts a JSON schema into the prompt and parses whatever
 * comes back. gpt-4o-mini at temperature 0.3 occasionally returns a partial
 * object — a missing "score", or an empty {} — and the two ways to fail were
 * both wrong:
 *   - primitive `double score` + Jackson 3 (FAIL_ON_NULL_FOR_PRIMITIVES is
 *     ON by default, unlike Jackson 2): MismatchedInputException, the whole
 *     fan-out failed with 500 while the LangChain4j twin returned normally;
 *   - boxed Double + "count null as 0.0": no error, but a candidate silently
 *     scored 0.0 with player=null, and the wrong candidate "won"
 *     (seen 2026-09-02: Jonathan David dropped, Zirkzee ranked first).
 * A silent wrong answer is worse than a loud failure for a demo about
 * observability. So: a branch that comes back unusable is RETRIED once with
 * a blunter prompt, and only if that also fails is it recorded as 0.0 with a
 * WARN and an explicit rationale — visible in the answer, in Loki, and as a
 * second chat span in the trace (the retry is part of the waterfall on
 * purpose: "one branch needed two calls" is exactly what a trace is for).
 *
 * The proper fix is OpenAI strict JSON-schema mode (responseFormat
 * JSON_SCHEMA in OpenAiChatOptions), which guarantees the shape; it is left
 * out here because it ties the pattern to one provider's option and the talk
 * compares frameworks, not providers.
 */
@Slf4j
@Service
public class ParallelizationPattern {

    /**
     * Boxed on purpose — a null here means "the model did not provide a
     * score", not "zero". See the class comment.
     */
    public record CandidateScore(String player, Double score, String rationale) {

        double scoreOrZero() {
            return score == null ? 0.0 : score;
        }

        boolean isUsable() {
            return player != null && !player.isBlank() && score != null;
        }
    }

    private static final int MAX_ATTEMPTS = 2;

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
                .map(rumor -> CompletableFuture.supplyAsync(() -> score(rumor), executor))
                .toList();

        // Barrier + aggregation in CODE (could also be a final LLM call)
        List<CandidateScore> scores = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        scores.forEach(s -> log.info("[PARALLEL] {} -> {}", s.player(), s.score()));

        CandidateScore best = scores.stream()
                .max(Comparator.comparingDouble(CandidateScore::scoreOrZero))
                .orElseThrow();

        return "Best candidate: " + best.player()
                + " (score " + best.score() + ") — " + best.rationale()
                + "\n\nAll scores: " + scores;
    }

    /** One branch of the fan-out — up to MAX_ATTEMPTS calls, see the class comment. */
    private CandidateScore score(MilanKnowledgeBase.Rumor rumor) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            CandidateScore parsed = plainAgent.prompt()
                    .user(prompt(rumor, attempt))
                    .call()
                    .entity(CandidateScore.class);

            if (parsed != null && parsed.isUsable()) {
                return parsed;
            }
            log.warn("[PARALLEL] attempt {}/{} for {} returned an unusable object: {}",
                    attempt, MAX_ATTEMPTS, rumor.player(), parsed);
        }
        log.warn("[PARALLEL] giving up on {} — recorded as 0.0", rumor.player());
        return new CandidateScore(rumor.player(), 0.0,
                "no structured answer after " + MAX_ATTEMPTS + " attempts");
    }

    private static String prompt(MilanKnowledgeBase.Rumor rumor, int attempt) {
        String base = "Score this transfer candidate for AC Milan from 0.0 to 1.0 "
                + "considering probability and squad needs. Candidate: " + rumor;
        if (attempt == 1) {
            return base;
        }
        // Retry: name the fields, name the player, leave nothing to infer.
        return base + "\nReturn a JSON object with EXACTLY these fields: "
                + "\"player\" (string, must be \"" + rumor.player() + "\"), "
                + "\"score\" (number between 0.0 and 1.0), "
                + "\"rationale\" (string). All three are mandatory.";
    }
}
