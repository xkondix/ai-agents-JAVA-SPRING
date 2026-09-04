package com.xkondix.patterns.springai.patterns;

import com.xkondix.common.milan.MilanKnowledgeBase;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * PATTERN 4 — Evaluator-optimizer (generate -> critique -> improve loop).
 *
 * A generator produces a solution once, an evaluator scores it against
 * explicit criteria and returns feedback, and a fixer improves the SAME
 * solution until the score crosses a threshold or the iterations run out.
 *
 * Spring AI implementation: a HAND-WRITTEN loop with a typed Evaluation
 * record. Compare with LangChain4j
 * loopBuilder().subAgents(scorer, fixer).exitCondition(...) — this is the
 * sharpest DSL-vs-plain-Java contrast in the whole set (and the same
 * MAX_ITERATIONS idea as raw-agent's loop).
 *
 * Trace signature: propose once, then N repetitions of the score+fix pair,
 * each wrapped in an "evaluator_iteration" span carrying the score — the
 * waterfall shows quality converging, not just time passing.
 *
 * ── THREE FIXES, ALL FOUND BY COMPARING THE TWO IMPLEMENTATIONS ─────────────
 *
 * 1. THE EVALUATOR NEVER SAW THE SQUAD.
 *    It was asked to check "uses only listed players" while the squad list
 *    was passed to the generator only. The criterion was unverifiable, so the
 *    model scored defensively, the threshold was never reached, and the loop
 *    always burned all four iterations — visible in the UI as the permanent
 *    "Best effort after 4 iterations", and in the metrics as roughly twice
 *    the calls and tokens of the LangChain4j twin (29 vs 13 calls measured on
 *    2026-09-02). The LangChain4j scorer had it right:
 *    "Score this lineup ... Squad: {{squadData}}\nLineup: {{solution}}".
 *
 * 2. THE TWO MODULES RAN DIFFERENT ALGORITHMS UNDER ONE PATTERN NAME.
 *    This one REGENERATED the lineup from scratch on every lap (generator +
 *    evaluator), while LangChain4j proposes once and then only FIXES
 *    (proposer, then loop of scorer + fixer). Same label, different work,
 *    so the side-by-side timings in Patterns Lab compared two different
 *    things — and the slower one looked like a framework weakness.
 *    Propose-once-then-improve is also closer to the original pattern in
 *    Anthropic's "Building Effective Agents".
 *
 * 3. THE THRESHOLD WAS TOO LOW TO SHOW A LOOP.
 *    With the squad in the prompt, a good first proposal scored exactly 0.8
 *    and the loop exited immediately — "Accepted after 1 iteration(s)
 *    (score 0.8)", verified live on 2026-09-03. The pattern worked and
 *    demonstrated nothing. 0.85 sits deliberately in the GAP between what
 *    the scoring rules grant a correct lineup (0.8) and what they grant a
 *    lineup that also lists shirt numbers and states the formation (0.9), so
 *    the first pass fails, the fixer adds exactly those two things, and the
 *    second pass clears the bar. That is the whole reason the two numbers
 *    differ: 0.9 is a QUALITY rule for the scorer, 0.85 is the ACCEPTANCE
 *    bar for the loop.
 *
 * ── WHY THE SCORE IS ON A SPAN HERE AND NOT IN THE LANGCHAIN4J TWIN ─────────
 *
 * A hand-written loop owns its control flow, so the iteration counter and
 * the score are ordinary local variables — putting them on a span is three
 * lines. The Agentic DSL owns the loop instead: the score lives in the
 * AgenticScope and the iteration boundary is inside loopBuilder(), with no
 * callback to hook. The DSL gives away less of the middle.
 *
 * That is not a criticism of either side. It is the same trade this whole
 * project is about, showing up one level down: declarative code is shorter
 * and less observable, imperative code is longer and lets you instrument
 * exactly where you want. Worth saying out loud when the two waterfalls are
 * side by side and only one of them carries scores.
 *
 * Tracer is resolved through ObjectProvider and may be null — a pattern demo
 * must not fail to start because tracing is switched off.
 */
@Slf4j
@Service
public class EvaluatorOptimizerPattern {

    public record Evaluation(double score, String feedback) {}

    private static final int MAX_ITERATIONS = 4;

    /**
     * Kept in sync with patterns-langchain4j and with the flow diagram in
     * chat-ui. It is a DEMO PARAMETER, not a quality bar: it decides whether
     * this pattern shows a loop or a straight line. Three places, one value.
     */
    private static final double THRESHOLD = 0.85;

    private final ChatClient plainAgent;
    private final @Nullable Tracer tracer;

    public EvaluatorOptimizerPattern(@Qualifier("plainAgent") ChatClient plainAgent,
                                     ObjectProvider<Tracer> tracerProvider) {
        this.plainAgent = plainAgent;
        this.tracer = tracerProvider.getIfAvailable();
    }

    /**
     * Scope handle for one loop iteration.
     *
     * NOT AutoCloseable: its close() declares `throws Exception`, so
     * try-with-resources forces the caller to catch a checked exception that
     * can never be thrown here — a compile error for an impossible failure.
     * Tracer.SpanInScope closes silently and so does the no-op below, so the
     * interface says exactly that.
     */
    @FunctionalInterface
    private interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    public String run(int seasonYear) {
        var squad = MilanKnowledgeBase.squad(seasonYear);
        if (squad.isEmpty()) {
            return "No data for season " + seasonYear;
        }
        log.info("[EVALUATOR] season={} threshold={} — starting review loop",
                seasonYear, THRESHOLD);

        // Generator — runs ONCE, outside the loop (mirrors the LC4j proposer)
        String solution = plainAgent.prompt()
                .user("Propose a starting XI formation and lineup for AC Milan "
                        + seasonYear + " using ONLY players from this list "
                        + "(name and shirt number): " + squad)
                .call()
                .content();

        for (int i = 1; i <= MAX_ITERATIONS; i++) {
            // One span per lap. The two chat spans of this iteration nest
            // inside it, so the waterfall groups the pair instead of showing
            // a flat run of chats — and the score rides along as an attribute.
            Span span = startIterationSpan(i);
            try (Scope ignored = inScope(span)) {

                Evaluation eval = evaluate(squad.toString(), solution);
                log.info("[EVALUATOR] iteration {} score={} feedback={}",
                        i, eval.score(), eval.feedback());

                if (span != null) {
                    span.tag("evaluator.score", String.valueOf(eval.score()));
                    span.tag("evaluator.accepted", String.valueOf(eval.score() >= THRESHOLD));
                }

                if (eval.score() >= THRESHOLD) {
                    return "Accepted after " + i + " iteration(s) (score "
                            + eval.score() + "):\n" + solution;
                }

                solution = fix(squad.toString(), solution, eval.feedback());

            } finally {
                if (span != null) {
                    span.end();
                }
            }
        }
        return "Best effort after " + MAX_ITERATIONS + " iterations:\n" + solution;
    }

    /**
     * Explicit criteria, typed verdict. The squad goes in TOO: without it
     * "uses only listed players" cannot be checked and the score is a guess.
     * The rules mirror the LangChain4j scorer so both modules are graded on
     * the same scale.
     */
    private Evaluation evaluate(String squad, String solution) {
        return plainAgent.prompt()
                .user("""
                        Score this lineup from 0.0 to 1.0. Rules, applied in order:
                        - any player NOT in the squad list, or any placeholder such as
                          "TBD" / "not listed" / "could be": score at most 0.3
                        - fewer or more than 11 starters: score at most 0.4
                        - GK, DEF, MID and ATT all covered and the formation coherent: 0.8 or above
                        - reserve 0.9 and above for a lineup that also names shirt numbers
                          for every starter and states the formation explicitly
                        Return the score and concrete feedback."""
                        + "\nSquad: " + squad
                        + "\nLineup: " + solution)
                .call()
                .entity(Evaluation.class);
    }

    /** Improves the EXISTING lineup instead of starting over. */
    private String fix(String squad, String solution, String feedback) {
        return plainAgent.prompt()
                .user("Rewrite this lineup so that it has exactly 11 starters, every one "
                        + "of them taken from the squad list (no placeholders, no outsiders), "
                        + "all lines covered, the formation stated explicitly and a shirt "
                        + "number next to every starter. Address this feedback: " + feedback
                        + "\nSquad: " + squad
                        + "\nCurrent lineup: " + solution)
                .call()
                .content();
    }

    private @Nullable Span startIterationSpan(int iteration) {
        if (tracer == null) {
            return null;
        }
        return tracer.spanBuilder()
                .name("evaluator_iteration " + iteration)
                .tag("evaluator.iteration", String.valueOf(iteration))
                .tag("evaluator.threshold", String.valueOf(THRESHOLD))
                .tag("framework", "spring-ai")
                .start();
    }

    /** Null-safe scope: without a tracer there is nothing to enter or close. */
    private Scope inScope(@Nullable Span span) {
        if (tracer == null || span == null) {
            return () -> { };
        }
        Tracer.SpanInScope scope = tracer.withSpan(span);
        return scope::close;
    }
}
