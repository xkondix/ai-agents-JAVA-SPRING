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
 * loopBuilder().exitCondition(scope -> scope.readState("score", 0.0) >= 0.8)
 * .maxIterations(4). The loop, the state handoff and the stop condition are
 * all declarative. Compare with patterns-spring-ai, where the same pattern
 * is a hand-written while loop — the sharpest DSL-vs-plain-Java contrast
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
 * Trace signature: N repetitions of the chat+chat pair.
 */
@Slf4j
@Service
public class EvaluatorOptimizerPattern {

    public interface LineupProposer {
        @Agent("Proposes a starting XI for a squad")
        @UserMessage("Propose a starting XI formation and lineup for AC Milan "
                + "based on: {{squadData}}")
        String propose(@V("squadData") String squadData);
    }

    public interface LineupScorer {
        @Agent("Scores a lineup proposal from 0.0 to 1.0")
        @UserMessage("Score this lineup from 0.0 to 1.0 (uses only listed players, "
                + "covers GK/DEF/MID/ATT, coherent formation). Return ONLY the number.\n"
                + "Squad: {{squadData}}\nLineup: {{solution}}")
        double score(@V("squadData") String squadData, @V("solution") String solution);
    }

    public interface LineupFixer {
        @Agent("Improves a lineup proposal")
        @UserMessage("Improve this lineup so it uses only listed players and covers "
                + "all lines. Squad: {{squadData}}\nCurrent lineup: {{solution}}")
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
                .maxIterations(4)
                .exitCondition(scope -> scope.readState("score", 0.0) >= 0.8)
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
        log.info("[EVALUATOR] season={} — starting review loop", seasonYear);
        return workflow.create(squad.toString());
    }
}
