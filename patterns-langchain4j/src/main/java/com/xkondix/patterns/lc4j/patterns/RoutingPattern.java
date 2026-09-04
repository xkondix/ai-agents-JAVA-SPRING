package com.xkondix.patterns.lc4j.patterns;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Predicate;

/**
 * PATTERN 2 — Routing.
 *
 * A cheap classifier decides which SPECIALIST handles the query.
 *
 * LangChain4j implementation: the Agentic DSL end to end. A router agent
 * writes its verdict into the shared state under "route"; a
 * conditionalBuilder() then activates exactly one specialist based on a
 * PREDICATE over that state. The two are composed with sequenceBuilder(),
 * so the whole dispatch is a declaration — there is no switch, and no glue
 * code reading the router's answer.
 *
 * Compare with patterns-spring-ai, where the same routing is
 * `switch (route) { case SQUAD -> ... }` over an enum. That is the point of
 * the pair: the *pattern* is identical, the *control flow* is either
 * declared or written by hand.
 *
 * WHY THIS WAS REWRITTEN. The previous version used plain AiServices plus a
 * Java switch and carried a comment saying "swapping to conditionalBuilder
 * is a 1:1 exercise". That was true, but it left the module claiming an
 * "Agentic DSL" badge in Patterns Lab while only two of the five patterns
 * actually used the DSL — and routing is the pattern where the DSL shows the
 * most, because branching on shared state is exactly what it exists for.
 *
 * TOOLS SURVIVE THE MOVE. AgentBuilder exposes toolProvider(), so the
 * specialists keep taking their tools from the instrumented ToolProvider
 * (ToolsConfig) rather than AiServices.tools(...). Every execution — the
 * approval-gated getSecretRumors included — still appears as a
 * "tool_call <name>" span. Losing that would have been a reason not to do
 * this rewrite at all.
 *
 * LESSON LEARNED — the agent interfaces MUST be public:
 * dev.langchain4j.agentic.internal.AgentInvoker calls them via
 * Method.invoke() from ANOTHER package and does not call setAccessible().
 * A package-private nested interface fails at RUNTIME with
 *   IllegalAccessException: ... cannot access a member of interface ...
 * even though the METHOD is public — what matters is the visibility of the
 * declaring interface. Plain AiServices is unaffected because it goes
 * through a proxy instead of reflective invocation. Same trap as in
 * PromptChainingPattern; it bites once per DSL pattern.
 *
 * LESSON LEARNED — a specialist prompt must COMMAND, not suggest:
 * "Use getSecretRumors" made the model answer "Yes, I can check that for
 * you — would you like me to?" instead of calling the tool, so the approval
 * gate never fired. Models treat capability questions ("do you know…?") as
 * conversation openers; the prompt has to forbid that explicitly.
 *
 * Trace signature: one SHORT "chat" (router) + one LONG "chat" (specialist).
 */
@Slf4j
@Service
public class RoutingPattern {

    public enum Route { SQUAD, TRANSFERS, RUMORS }

    private static final String ROUTE_KEY = "route";
    private static final String ANSWER_KEY = "answer";

    // ── Agents ───────────────────────────────────────────────────────────
    // All interfaces PUBLIC — see the class comment.

    public interface Router {
        @Agent("Classifies an AC Milan question into one specialist category")
        @UserMessage("Classify this AC Milan question into exactly one category "
                + "(SQUAD = players/lineups, TRANSFERS = confirmed moves/fees, "
                + "RUMORS = gossip/speculation): {{question}}")
        Route classify(@V("question") String question);
    }

    public interface SquadSpecialist {
        @Agent("Answers squad and lineup questions")
        @SystemMessage("You are a squad specialist. ALWAYS call getSquad "
                + "(and getPlayerStats when a player is mentioned) BEFORE answering. "
                + "Never invent data and never ask the user for permission to look "
                + "something up — just look it up and answer.")
        @UserMessage("{{question}}")
        String answer(@V("question") String question);
    }

    public interface TransferSpecialist {
        @Agent("Answers confirmed transfer questions")
        @SystemMessage("You are a transfer-market specialist. ALWAYS call "
                + "getTransfers BEFORE answering. Never ask whether you should "
                + "check — check first, then answer with the data.")
        @UserMessage("{{question}}")
        String answer(@V("question") String question);
    }

    public interface RumorSpecialist {
        @Agent("Answers rumor questions using the approval-gated tool")
        @SystemMessage("""
                You are an insider on AC Milan transfer rumors.
                ALWAYS call getSecretRumors FIRST — on every question about
                rumors, gossip or speculation, including yes/no questions such
                as "do you know any rumors?".
                NEVER answer with an offer like "would you like me to check?"
                and never ask for permission: the tool itself is gated by a
                human approval step, so calling it IS the way to ask.
                If the tool returns ACCESS DENIED, tell the user the
                information cannot be shared. Otherwise present the rumors and
                clearly mark them as unconfirmed.
                """)
        @UserMessage("{{question}}")
        String answer(@V("question") String question);
    }

    /** Typed entry point: classify, then let exactly one specialist answer. */
    public interface RoutedAnswerWorkflow {
        @Agent("Routes an AC Milan question to the right specialist and answers it")
        String handle(@V("question") String question);
    }

    private final RoutedAnswerWorkflow workflow;

    public RoutingPattern(ChatModel chatModel, ToolProvider milanToolProvider) {

        var router = AgenticServices.agentBuilder(Router.class)
                .chatModel(chatModel)
                .outputKey(ROUTE_KEY)
                .build();

        // Every specialist writes to the SAME key: exactly one of them runs,
        // so the workflow's output key is unambiguous.
        var squad = AgenticServices.agentBuilder(SquadSpecialist.class)
                .chatModel(chatModel)
                .toolProvider(milanToolProvider)
                .outputKey(ANSWER_KEY)
                .build();
        var transfers = AgenticServices.agentBuilder(TransferSpecialist.class)
                .chatModel(chatModel)
                .toolProvider(milanToolProvider)
                .outputKey(ANSWER_KEY)
                .build();
        var rumors = AgenticServices.agentBuilder(RumorSpecialist.class)
                .chatModel(chatModel)
                .toolProvider(milanToolProvider)
                .outputKey(ANSWER_KEY)
                .build();

        // THE DSL: the dispatch is a set of predicates over shared state.
        // The condition descriptions are not decoration — they show up in
        // the DSL's own diagnostics when no branch matches.
        var dispatch = AgenticServices.conditionalBuilder()
                .subAgents("route is SQUAD", routeIs(Route.SQUAD), squad)
                .subAgents("route is TRANSFERS", routeIs(Route.TRANSFERS), transfers)
                .subAgents("route is RUMORS", routeIs(Route.RUMORS), rumors)
                .outputKey(ANSWER_KEY)
                .build();

        this.workflow = AgenticServices.sequenceBuilder(RoutedAnswerWorkflow.class)
                .subAgents(router, dispatch)
                .outputKey(ANSWER_KEY)
                .build();
    }

    /**
     * Compares by NAME rather than by enum identity on purpose.
     *
     * The router declares Route as its return type, so the state normally
     * holds the enum — but the value passes through AgenticScope as an
     * untyped object, and a model that answers with a bare string would
     * otherwise silently match no branch. Comparing names accepts both and
     * costs nothing.
     */
    private static Predicate<AgenticScope> routeIs(Route route) {
        return scope -> route.name()
                .equals(String.valueOf(scope.readState(ROUTE_KEY, "")).trim());
    }

    public String run(String question) {
        log.info("[ROUTING] question='{}'", question);
        return workflow.handle(question);
    }
}
