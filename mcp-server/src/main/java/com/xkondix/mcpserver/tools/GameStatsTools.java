package com.xkondix.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Game statistics tools exposed via MCP.
 * Spring AI @Tool approach — JSON Schema generated automatically.
 */
@Slf4j
@Service
public class GameStatsTools {

    @Tool(description = """
            Returns game statistics: top score, average score, total games played.
            Supported game types: SNAKE, RACING.
            Optionally filter by user ID.
            """)
    public String get_game_stats(
            @ToolParam(description = "Type of game: SNAKE or RACING") String gameType,
            @ToolParam(description = "Optional user ID to filter stats. Leave empty for all users.") String userId) {
        log.info("[MCP] get_game_stats gameType={} userId={}", gameType, userId);
        String user = (userId == null || userId.isBlank()) ? "all" : userId;
        return String.format(
                "Stats for %s (user: %s): TopScore=4200, Avg=1850, Games=47",
                gameType, user);
    }
}
