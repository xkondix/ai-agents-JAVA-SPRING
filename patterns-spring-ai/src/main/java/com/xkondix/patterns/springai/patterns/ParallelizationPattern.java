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
 * ── JACKSON 3 AND THE STRUCTURED OUTPUT ─────────────────────────────────────
 *
 * CandidateScore.score used to be a primitive double. On Boot 3 / Jackson 2 a
 * response with the field missing or null quietly became 0.0; Jackson 3 ships
 * with FAIL_ON_NULL_FOR_PRIMITIVES enabled by default (Jackson 2 default was
 * off), so the same response now fails the whole fan-out:
 *
 *   MismatchedInputException: Cannot map `null` into type `double`
 *   (through reference chain: ParallelizationPattern$CandidateScore["score"])
 *
 * Seen live on 2026-09-02: one of three branches came back without "score",
 * BeanOutputConverter threw, CompletableFuture.join() rethrew it as a
 * CompletionException and the endpoint answered 500 — while the LangChain4j
 * twin, which has its own parser, returned normally. A silent
 * Jackson-2-to-3 behaviour change that no grep for old package names finds.
 *
 * The field is now a boxed Double and normalised right after parsing: a
 * missing score is logged and treated as 0.0 (the candidate simply cannot
 * win), and the aggregation never trips over a null. The model is also told
 * the field is mandatory — BeanOutputConverter already ships the JSON schema
 * with "required", but gpt-4o-mini at temperature 0.3 still drops it now
 * and then, so the code has to tolerate it.
 */
@Slf4j
@Service
public class ParallelizationPattern {

    /**
     * Boxed on purpose — see the class comment. A null here means "the model
     * did not provide a score", not "zero".
     */
    public record CandidateScore(String player, Double score, String rationale) {

        double scoreOrZero() {
            return score == null ? 0.0 : score;
        }
    }

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

    /**
     * One branch of the fan-out. The rumor is rendered through its toString()
     * in the prompt, exactly as the inline lambda did before this was
     * extracted — the record's fields (player, target, probability, note) are
     * what the model scores.
     */
    private CandidateScore score(MilanKnowledgeBase.Rumor rumor) {
        CandidateScore parsed = plainAgent.prompt()
                .user("Score this transfer candidate for AC Milan from 0.0 to 1.0 "
                        + "considering probability and squad needs. "
                        + "The numeric field \"score\" is mandatory. Candidate: " + rumor)
                .call()
                .entity(CandidateScore.class);

        if (parsed == null) {
            log.warn("[PARALLEL] model returned no parsable object for: {}", rumor);
            return new CandidateScore("unknown", 0.0, "no structured answer");
        }
        if (parsed.score() == null) {
            log.warn("[PARALLEL] model omitted \"score\" for {} — counting as 0.0", parsed.player());
            return new CandidateScore(parsed.player(), 0.0, parsed.rationale());
        }
        return parsed;
    }
}
