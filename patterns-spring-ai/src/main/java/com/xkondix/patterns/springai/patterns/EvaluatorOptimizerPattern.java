package com.xkondix.patterns.springai.patterns;

import com.xkondix.common.milan.MilanKnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * PATTERN 4 — Evaluator-optimizer (generate -> critique -> improve loop).
 *
 * A generator produces a solution, an evaluator scores it against explicit
 * criteria and returns feedback; the feedback goes back to the generator
 * until the score crosses a threshold or iterations run out.
 *
 * Spring AI implementation: a HAND-WRITTEN while loop with a typed
 * Evaluation record. Compare with LangChain4j loopBuilder().exitCondition()
 * — this is the sharpest DSL-vs-plain-Java contrast in the whole set
 * (and the same MAX_ITERATIONS idea as raw-agent's loop).
 *
 * Trace signature: N repetitions of the chat+chat pair; put the score in
 * a span attribute and you can watch quality converge on the waterfall.
 *
 * Demo: iterate a starting XI for a season until the evaluator accepts it.
 */
@Slf4j
@Service
public class EvaluatorOptimizerPattern {

    public record Evaluation(double score, String feedback) {}

    private static final int MAX_ITERATIONS = 4;
    private static final double THRESHOLD = 0.8;

    private final ChatClient plainAgent;

    public EvaluatorOptimizerPattern(@Qualifier("plainAgent") ChatClient plainAgent) {
        this.plainAgent = plainAgent;
    }

    public String run(int seasonYear) {
        var squad = MilanKnowledgeBase.squad(seasonYear);
        if (squad.isEmpty()) {
            return "No data for season " + seasonYear;
        }

        String solution = null;
        String feedback = "none yet — first attempt";

        for (int i = 1; i <= MAX_ITERATIONS; i++) {
            // Generator — receives the evaluator's feedback from the last lap
            solution = plainAgent.prompt()
                    .user("Propose a starting XI formation and lineup for AC Milan "
                            + seasonYear + " based on: " + squad
                            + "\nPrevious feedback to address: " + feedback)
                    .call()
                    .content();

            // Evaluator — explicit criteria, typed verdict
            Evaluation eval = plainAgent.prompt()
                    .user("Evaluate this lineup proposal from 0.0 to 1.0. Criteria: "
                            + "uses only listed players, covers GK/DEF/MID/ATT, "
                            + "formation is coherent. Return score and concrete feedback.\n"
                            + solution)
                    .call()
                    .entity(Evaluation.class);

            log.info("[EVALUATOR] iteration {} score={} feedback={}",
                    i, eval.score(), eval.feedback());

            if (eval.score() >= THRESHOLD) {
                return "Accepted after " + i + " iteration(s) (score "
                        + eval.score() + "):\n" + solution;
            }
            feedback = eval.feedback();
        }
        return "Best effort after " + MAX_ITERATIONS + " iterations:\n" + solution;
    }
}
