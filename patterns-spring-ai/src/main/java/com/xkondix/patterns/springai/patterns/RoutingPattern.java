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
 * LESSON LEARNED #1: converting to a BARE ENUM (.entity(Route.class)) is
 * fragile — models return the value quoted, wrapped in JSON or with
 * punctuation, and the converter throws. Wrapping the enum in a record
 * gives the converter a proper object schema and works reliably; a string
 * fallback keeps the router alive even if conversion still hiccups.
 *
 * LESSON LEARNED #2: a specialist prompt must COMMAND, not suggest.
 * "Use getSecretRumors" made the model reply "Yes, I can check that — would
 * you like me to?" instead of calling the tool, so the human approval gate
 * never fired. Capability questions ("do you know…?") invite conversation;
 * the prompt has to forbid that explicitly.
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
                    .system("""
                            You are a squad specialist. ALWAYS call getSquad
                            (and getPlayerStats when a player is mentioned)
                            BEFORE answering. Never invent data and never ask
                            for permission to look something up — just do it.
                            """)
                    .user(question)
                    .call().content();
            case TRANSFERS -> milanAgent.prompt()
                    .system("""
                            You are a transfer-market specialist. ALWAYS call
                            getTransfers BEFORE answering. Never ask whether you
                            should check — check first, then answer with data.
                            """)
                    .user(question)
                    .call().content();
            case RUMORS -> milanAgent.prompt()
                    .system("""
                            You are an insider on AC Milan transfer rumors.
                            ALWAYS call getSecretRumors FIRST — on every question
                            about rumors, gossip or speculation, including yes/no
                            questions such as "do you know any rumors?".
                            NEVER answer with an offer like "would you like me to
                            check?" and never ask for permission: the tool itself
                            is gated by a human approval step, so calling it IS
                            the way to ask.
                            If the tool returns ACCESS DENIED, tell the user the
                            information cannot be shared. Otherwise present the
                            rumors and clearly mark them as unconfirmed.
                            """)
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
