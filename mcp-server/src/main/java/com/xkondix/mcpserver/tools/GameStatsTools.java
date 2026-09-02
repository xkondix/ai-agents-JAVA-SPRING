package com.xkondix.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * Game statistics tools exposed via MCP.
 *
 * SPRING AI 2.0 — @McpTool, NOT @Tool.
 * @Tool + MethodToolCallbackProvider described a tool for an AGENT's own tool
 * loop; exposing it over MCP was a side effect of that registration. In 2.0 the
 * MCP server has its own annotation and its own scanner, so the two concerns
 * are separate: @Tool stays for local tools inside an agent, @McpTool is the
 * wire contract of an MCP server.
 *
 * Tool names are declared EXPLICITLY. Derivation from the method name works,
 * but it makes the wire contract a side effect of a refactor — rename the
 * method and every connected client silently loses a tool.
 */
@Slf4j
@Service
public class GameStatsTools {

    @McpTool(name = "get_game_stats", description = """
            Returns game statistics: top score, average score, total games played.
            Supported game types: SNAKE, RACING.
            Optionally filter by user ID.
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get_game_stats(
            @McpToolParam(description = "Type of game: SNAKE or RACING", required = true)
            String gameType,
            @McpToolParam(description = "Optional user ID to filter stats. Leave empty for all users.",
                    required = false)
            String userId) {
        log.info("[MCP] get_game_stats gameType={} userId={}", gameType, userId);
        String user = (userId == null || userId.isBlank()) ? "all" : userId;
        return String.format(
                "Stats for %s (user: %s): TopScore=4200, Avg=1850, Games=47",
                gameType, user);
    }
}
