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
 *
 * ── A THROWN BRANCH MUST NOT TAKE DOWN THE FAN-OUT ──────────────────────────
 *
 * The retry above covers a branch that ANSWERS BADLY. It does not cover a
 * branch that does not answer at all: a timeout, a 429, a dropped connection.
 * Those escape score(), CompletableFuture.join() rethrows them wrapped in a
 * CompletionException, and one failed candidate out of five turns the whole
 * request into a 500 — with the original cause one level deeper than usual,
 * so the HTTP response says nothing useful.
 *
 * That is not hypothetical here: OpenRouter latency has been observed between
 * 8 and 47 seconds, and this is the one pattern that fires every branch at
 * once. On stage it is also the worst possible failure — four good scores
 * discarded because the fifth timed out.
 *
 * So each branch catches its own exception and degrades to a 0.0 entry that
 * NAMES the failure. The aggregate still answers, the trace still shows the
 * failed span, and the response text admits which candidate could not be
 * scored. Partial results beat no results — and saying so out loud beats
 * quietly pretending the candidate scored zero.
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
        if (candidates.isEmpty()) {
            return "No transfer rumors to score.";
        }
        log.info("[PARALLEL] fan-out over {} candidates", candidates.size());

        // Fan-out — one LLM call per candidate, all at once
        List<CompletableFuture<CandidateScore>> futures = candidates.stream()
                .map(rumor -> CompletableFuture.supplyAsync(() -> score(rumor), executor))
                .toList();

        // Barrier + aggregation in CODE (could also be a final LLM call).
        // join() is safe here only because score() never throws — see the
        // class comment; without that guarantee one slow branch is a 500.
        List<CandidateScore> scores = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        scores.forEach(s -> log.info("[PARALLEL] {} -> {}", s.player(), s.score()));

        CandidateScore best = scores.stream()
                .max(Comparator.comparingDouble(CandidateScore::scoreOrZero))
                .orElseThrow();

        long failed = scores.stream().filter(s -> !s.isUsable() || s.scoreOrZero() == 0.0).count();
        String note = failed == 0 ? "" :
                "\n\nNote: " + failed + " of " + scores.size()
                        + " branches could not be scored — see rationale.";

        return "Best candidate: " + best.player()
                + " (score " + best.score() + ") — " + best.rationale()
                + "\n\nAll scores: " + scores + note;
    }

    /**
     * One branch of the fan-out — up to MAX_ATTEMPTS calls, see the class
     * comment. NEVER THROWS: a branch that fails is worth 0.0, not a failed
     * request for the other four.
     */
    private CandidateScore score(MilanKnowledgeBase.Rumor rumor) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                CandidateScore parsed = plainAgent.prompt()
                        .user(prompt(rumor, attempt))
                        .call()
                        .entity(CandidateScore.class);

                if (parsed != null && parsed.isUsable()) {
                    return parsed;
                }
                log.warn("[PARALLEL] attempt {}/{} for {} returned an unusable object: {}",
                        attempt, MAX_ATTEMPTS, rumor.player(), parsed);

            } catch (RuntimeException e) {
                // Timeout, 429, dropped connection, unparsable payload — the
                // retry is worth a shot, but the fan-out is not worth losing.
                log.warn("[PARALLEL] attempt {}/{} for {} failed: {}: {}",
                        attempt, MAX_ATTEMPTS, rumor.player(),
                        e.getClass().getSimpleName(), e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    return new CandidateScore(rumor.player(), 0.0,
                            "could not be scored — " + e.getClass().getSimpleName());
                }
            }
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
