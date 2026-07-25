package com.xkondix.patterns.springai.tools;

import com.xkondix.common.approval.HumanApprovalService;
import com.xkondix.common.milan.MilanKnowledgeBase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * AC Milan domain tools — Spring AI flavour (@Tool from spring-ai).
 * Thin wrappers over the shared MilanKnowledgeBase in common.
 *
 * getSecretRumors is wrapped in the shared approval gate: the guarded work
 * is a lambda passed to gate(...), so the disclosure CANNOT happen without
 * a human decision. The call blocks until someone approves in the Chat UI —
 * on the Tempo waterfall that shows up as a tool span growing in real time.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilanTools {

    private final HumanApprovalService approvalService;

    @Tool(description = "Returns the AC Milan squad for a given season year "
            + "(available: 2007, 2024) with positions and ratings")
    public String getSquad(@ToolParam(description = "Season year, e.g. 2007") int year) {
        log.info("[TOOL] getSquad year={}", year);
        var squad = MilanKnowledgeBase.squad(year);
        return squad.isEmpty()
                ? "No data for season " + year + ". Available: "
                    + MilanKnowledgeBase.availableSeasons()
                : squad.toString();
    }

    @Tool(description = "Returns AC Milan transfers; filter by window, "
            + "e.g. '2006' or 'summer'. Empty filter returns all.")
    public String getTransfers(@ToolParam(description = "Window filter, may be empty") String window) {
        log.info("[TOOL] getTransfers window={}", window);
        return MilanKnowledgeBase.transfers(window).toString();
    }

    @Tool(description = "Returns stats (position, shirt number, rating) for a player by name")
    public String getPlayerStats(@ToolParam(description = "Player full name") String name) {
        log.info("[TOOL] getPlayerStats name={}", name);
        var player = MilanKnowledgeBase.playerStats(name);
        return player != null ? player.toString() : "Unknown player: " + name;
    }

    @Tool(description = "SECRET transfer rumors with insider notes. "
            + "Confidential — requires human approval before disclosure. "
            + "Use only when the user explicitly asks about rumors.")
    public String getSecretRumors() {
        log.info("[TOOL] getSecretRumors — requesting human approval");

        return approvalService.gate(
                "SECRET_RUMORS", "getSecretRumors",
                "Agent wants to disclose CONFIDENTIAL transfer rumors",
                "Source: patterns-spring-ai (port 8088)\n"
                        + "Tool: getSecretRumors()\n"
                        + "Returns insider notes that are not public information.",
                () -> MilanKnowledgeBase.secretRumors().toString(),
                // The refusal goes back to the MODEL as a normal tool result —
                // it will explain to the user that access was denied.
                "ACCESS DENIED: a human reviewer rejected disclosure of the "
                        + "confidential rumors (or the request timed out). "
                        + "Tell the user you cannot share them.");
    }
}
