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
        @SystemMessage("You are a squad specialist. Use getSquad/getPlayerStats. "
                + "Never invent data.")
        String answer(@UserMessage String question);
    }

    interface TransferSpecialist {
        @SystemMessage("You are a transfer-market specialist. Use getTransfers.")
        String answer(@UserMessage String question);
    }

    interface RumorSpecialist {
        @SystemMessage("You are an insider. Use getSecretRumors and clearly "
                + "mark everything as unconfirmed rumor.")
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
