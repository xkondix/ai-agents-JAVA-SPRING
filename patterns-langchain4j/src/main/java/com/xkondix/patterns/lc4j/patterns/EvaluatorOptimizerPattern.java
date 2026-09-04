package com.xkondix.patterns.lc4j.patterns;

import com.xkondix.common.milan.MilanKnowledgeBase;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PATTERN 4 — Evaluator-optimizer (generate -> critique -> improve loop).
 *
 * LangChain4j implementation: THE flagship of the Agentic DSL —
 * loopBuilder().exitCondition(scope -> scope.readState("score", 0.0) >= THRESHOLD)
 * .maxIterations(4). The loop, the state handoff and the stop condition are
 * all declarative. Compare with patterns-spring-ai, where the same pattern
 * is a hand-written for loop — the sharpest DSL-vs-plain-Java contrast
 * in the whole set (and the raw-agent MAX_ITERATIONS idea as a 1st-class API).
 *
 * Structure mirrors the Devoxx "Loop Workflow" example:
 *   sequence( proposer, loop(scorer, fixer) ) -> outputKey "solution"
 *
 * NOTE: the agent interfaces are PUBLIC on purpose — the agentic runtime
 * invokes them reflectively from another package (AgentInvoker.invoke ->
 * Method.invoke) without setAccessible(), so package-private interfaces
 * blow up with IllegalAccessException before any LLM call happens.
 *
 * THE SCORER MUST BE STRICT, OR THE LOOP NEVER LOOPS. With 8 players in the
 * 2007 squad a legal XI was impossible; the proposer wrote "RB: TBD (not
 * listed, could be Cafu)", the scorer still returned >= 0.8 and the loop
 * exited after one pass — the fixer never ran, so the trace showed the
 * "chat+chat pair" exactly zero times. Two fixes: MilanKnowledgeBase now
 * holds a full XI plus bench per season, and the scorer prompt below makes
 * an outsider or a placeholder an automatic fail. A loop demo is only a demo
 * when the first proposal can lose.
 *
 * THE THRESHOLD IS A DEMO PARAMETER, NOT A QUALITY BAR. At 0.8 the scoring
 * rules below hand out exactly 0.8 for a correct lineup, so a good first
 * proposal exits immediately — verified live on 2026-09-03, where Spring AI
 * reported "Accepted after 1 iteration(s) (score 0.8)". The pattern was
 * working and demonstrating nothing: the trace hint promises "one chat, then
 * N repetitions of a score+fix pair" and the waterfall showed a single pair.
 * 0.85 sits deliberately ABOVE what the rules grant on the first pass, so
 * the fixer has to run at least once and the loop is visible. Both modules
 * use the same number on purpose — it is the one knob that decides whether
 * this demo shows a loop or a straight line.
 *
 * Trace signature: N repetitions of the chat+chat pair.
 */
@Slf4j
@Service
public class EvaluatorOptimizerPattern {

    /** Kept in sync with patterns-spring-ai and with the flow diagram in chat-ui. */
    private static final double THRESHOLD = 0.85;
    private static final int MAX_ITERATIONS = 4;

    public interface LineupProposer {
        @Agent("Proposes a starting XI for a squad")
        @UserMessage("Propose a starting XI formation and lineup for AC Milan "
                + "using ONLY players from this list (name and shirt number): {{squadData}}")
        String propose(@V("squadData") String squadData);
    }

    public interface LineupScorer {
        @Agent("Scores a lineup proposal from 0.0 to 1.0")
        @UserMessage("""
                Score this lineup from 0.0 to 1.0. Rules, applied in order:
                - any player NOT in the squad list, or any placeholder such as
                  "TBD" / "not listed" / "could be": score at most 0.3
                - fewer or more than 11 starters: score at most 0.4
                - GK, DEF, MID and ATT all covered and the formation coherent: 0.8 or above
                - reserve 0.9 and above for a lineup that also names shirt numbers
                  for every starter and states the formation explicitly
                Return ONLY the number.
                Squad: {{squadData}}
                Lineup: {{solution}}""")
        double score(@V("squadData") String squadData, @V("solution") String solution);
    }

    public interface LineupFixer {
        @Agent("Improves a lineup proposal")
        @UserMessage("Rewrite this lineup so that it has exactly 11 starters, every one "
                + "of them taken from the squad list (no placeholders, no outsiders), "
                + "all lines covered, the formation stated explicitly and a shirt number "
                + "next to every starter. Squad: {{squadData}}\nCurrent lineup: {{solution}}")
        String fix(@V("squadData") String squadData, @V("solution") String solution);
    }

    /** Typed entry point: proposer once, then the review loop. */
    public interface LineupWorkflow {
        @Agent("Produces an accepted starting XI")
        String create(@V("squadData") String squadData);
    }

    private final LineupWorkflow workflow;

    public EvaluatorOptimizerPattern(ChatModel chatModel) {
        var proposer = AgenticServices.agentBuilder(LineupProposer.class)
                .chatModel(chatModel)
                .outputKey("solution")
                .build();
        var scorer = AgenticServices.agentBuilder(LineupScorer.class)
                .chatModel(chatModel)
                .outputKey("score")
                .build();
        var fixer = AgenticServices.agentBuilder(LineupFixer.class)
                .chatModel(chatModel)
                .outputKey("solution")
                .build();

        // Declarative loop — straight from the Devoxx slide
        var reviewLoop = AgenticServices.loopBuilder()
                .subAgents(scorer, fixer)
                .maxIterations(MAX_ITERATIONS)
                .exitCondition(scope -> scope.readState("score", 0.0) >= THRESHOLD)
                .build();

        this.workflow = AgenticServices.sequenceBuilder(LineupWorkflow.class)
                .subAgents(proposer, reviewLoop)
                .outputKey("solution")
                .build();
    }

    public String run(int seasonYear) {
        var squad = MilanKnowledgeBase.squad(seasonYear);
        if (squad.isEmpty()) {
            return "No data for season " + seasonYear;
        }
        log.info("[EVALUATOR] season={} threshold={} — starting review loop",
                seasonYear, THRESHOLD);
        return workflow.create(squad.toString());
    }
}
