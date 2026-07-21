package com.xkondix.patterns.springai.patterns;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * PATTERN 2 — Routing.
 *
 * A cheap classifier decides which SPECIALIST prompt handles the query.
 * Separation of concerns: the router only recognizes, specialists answer.
 *
 * Spring AI implementation highlight: structured output — the classifier
 * returns a typed result via .entity(...), then a plain switch.
 *
 * LESSON LEARNED: converting to a BARE ENUM (.entity(Route.class)) is
 * fragile — models return the value quoted, wrapped in JSON or with
 * punctuation, and the converter throws. Wrapping the enum in a record
 * gives the converter a proper object schema and works reliably; a string
 * fallback keeps the router alive even if conversion still hiccups.
 *
 * Trace signature: one SHORT "chat" (router) + one LONG "chat" (specialist)
 * — the time asymmetry is the fingerprint of this pattern.
 */
@Slf4j
@Service
public class RoutingPattern {

    public enum Route { SQUAD, TRANSFERS, RUMORS }

    /** Object wrapper — far more reliable for structured output than a bare enum. */
    public record RouteChoice(Route route) {}

    private final ChatClient plainAgent;
    private final ChatClient milanAgent;

    public RoutingPattern(@Qualifier("plainAgent") ChatClient plainAgent,
                          @Qualifier("milanAgent") ChatClient milanAgent) {
        this.plainAgent = plainAgent;
        this.milanAgent = milanAgent;
    }

    public String run(String question) {
        Route route = classify(question);
        log.info("[ROUTING] '{}' -> {}", question, route);

        // Dispatch — each specialist has its own system prompt (and tools)
        return switch (route) {
            case SQUAD -> milanAgent.prompt()
                    .system("You are a squad specialist. Use getSquad/getPlayerStats.")
                    .user(question)
                    .call().content();
            case TRANSFERS -> milanAgent.prompt()
                    .system("You are a transfer-market specialist. Use getTransfers.")
                    .user(question)
                    .call().content();
            case RUMORS -> milanAgent.prompt()
                    .system("You are an insider. Use getSecretRumors and clearly "
                            + "mark everything as unconfirmed rumor.")
                    .user(question)
                    .call().content();
        };
    }

    private Route classify(String question) {
        String prompt = "Classify this AC Milan question into exactly one category "
                + "(SQUAD = players/lineups, TRANSFERS = confirmed moves/fees, "
                + "RUMORS = gossip/speculation): " + question;
        try {
            RouteChoice choice = plainAgent.prompt()
                    .user(prompt)
                    .call()
                    .entity(RouteChoice.class);
            if (choice != null && choice.route() != null) {
                return choice.route();
            }
        } catch (Exception e) {
            log.warn("[ROUTING] structured classification failed ({}), "
                    + "falling back to string parsing", e.toString());
        }
        // Fallback: plain text answer, normalize and match
        String raw = plainAgent.prompt()
                .user(prompt + " Answer with ONLY the category name.")
                .call().content();
        String normalized = raw == null ? "" : raw.toUpperCase();
        if (normalized.contains("TRANSFER")) return Route.TRANSFERS;
        if (normalized.contains("RUMOR")) return Route.RUMORS;
        return Route.SQUAD; // sensible default for a Milan bot
    }
}
