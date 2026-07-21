package com.xkondix.patterns.lc4j.patterns;

import com.xkondix.patterns.lc4j.tools.MilanTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PATTERN 2 — Routing.
 *
 * A cheap classifier decides which SPECIALIST handles the query.
 *
 * LangChain4j implementation: the router is an AiServices interface that
 * returns an ENUM directly (LC4j parses it for you) + an explicit switch.
 * The Agentic DSL also offers conditionalBuilder() with predicates on the
 * shared state — we use the switch here because it makes the dispatch
 * visible for the demo; swapping to conditionalBuilder is a 1:1 exercise.
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

    interface Router {
        @UserMessage("Classify this AC Milan question into exactly one category "
                + "(SQUAD = players/lineups, TRANSFERS = confirmed moves/fees, "
                + "RUMORS = gossip/speculation): {{it}}")
        Route classify(String question);
    }

    interface SquadSpecialist {
        @SystemMessage("You are a squad specialist. ALWAYS call getSquad "
                + "(and getPlayerStats when a player is mentioned) BEFORE answering. "
                + "Never invent data and never ask the user for permission to look "
                + "something up — just look it up and answer.")
        String answer(@UserMessage String question);
    }

    interface TransferSpecialist {
        @SystemMessage("You are a transfer-market specialist. ALWAYS call "
                + "getTransfers BEFORE answering. Never ask whether you should "
                + "check — check first, then answer with the data.")
        String answer(@UserMessage String question);
    }

    interface RumorSpecialist {
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
        String answer(@UserMessage String question);
    }

    private final Router router;
    private final SquadSpecialist squadSpecialist;
    private final TransferSpecialist transferSpecialist;
    private final RumorSpecialist rumorSpecialist;

    public RoutingPattern(ChatModel chatModel, MilanTools milanTools) {
        this.router = AiServices.builder(Router.class)
                .chatModel(chatModel)
                .build();
        this.squadSpecialist = AiServices.builder(SquadSpecialist.class)
                .chatModel(chatModel).tools(milanTools).build();
        this.transferSpecialist = AiServices.builder(TransferSpecialist.class)
                .chatModel(chatModel).tools(milanTools).build();
        this.rumorSpecialist = AiServices.builder(RumorSpecialist.class)
                .chatModel(chatModel).tools(milanTools).build();
    }

    public String run(String question) {
        Route route = router.classify(question);
        log.info("[ROUTING] '{}' -> {}", question, route);
        return switch (route) {
            case SQUAD -> squadSpecialist.answer(question);
            case TRANSFERS -> transferSpecialist.answer(question);
            case RUMORS -> rumorSpecialist.answer(question);
        };
    }
}
