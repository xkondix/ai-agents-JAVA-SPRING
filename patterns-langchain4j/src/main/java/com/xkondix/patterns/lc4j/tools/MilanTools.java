package com.xkondix.patterns.lc4j.tools;

import com.xkondix.common.milan.MilanKnowledgeBase;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * AC Milan domain tools — LangChain4j flavour (@Tool from dev.langchain4j).
 * Thin wrappers over the shared MilanKnowledgeBase in common — the SAME
 * data the patterns-spring-ai module exposes with Spring AI annotations.
 */
@Slf4j
@Component
public class MilanTools {

    @Tool("Returns the AC Milan squad for a given season year "
            + "(available: 2007, 2024) with positions and ratings")
    public String getSquad(@P("Season year, e.g. 2007") int year) {
        log.info("[TOOL] getSquad year={}", year);
        var squad = MilanKnowledgeBase.squad(year);
        return squad.isEmpty()
                ? "No data for season " + year + ". Available: "
                    + MilanKnowledgeBase.availableSeasons()
                : squad.toString();
    }

    @Tool("Returns AC Milan transfers; filter by window, e.g. '2006' or 'summer'. "
            + "Empty filter returns all.")
    public String getTransfers(@P("Window filter, may be empty") String window) {
        log.info("[TOOL] getTransfers window={}", window);
        return MilanKnowledgeBase.transfers(window).toString();
    }

    @Tool("Returns stats (position, shirt number, rating) for a player by name")
    public String getPlayerStats(@P("Player full name") String name) {
        log.info("[TOOL] getPlayerStats name={}", name);
        var player = MilanKnowledgeBase.playerStats(name);
        return player != null ? player.toString() : "Unknown player: " + name;
    }

    @Tool("SECRET transfer rumors with insider notes. Confidential — "
            + "use only when the user explicitly asks about rumors.")
    public String getSecretRumors() {
        log.info("[TOOL] getSecretRumors (confidential access)");
        return MilanKnowledgeBase.secretRumors().toString();
    }
}
