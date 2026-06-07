package com.xkondix.mcpserver.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
public class GameStatsTools {

    public McpServerFeatures.SyncToolSpecification getGameStatsTool() {

        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "gameType": {
                      "type": "string",
                      "description": "Type of game: SNAKE or RACING"
                    },
                    "userId": {
                      "type": "string",
                      "description": "Optional user ID to filter stats"
                    }
                  },
                  "required": ["gameType"]
                }
                """;

        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "get_game_stats",
                        "Returns statistics: top score, avg score, total games played.",
                        schema),
                (exchange, args) -> {
                    String gameType = (String) args.getOrDefault("gameType", "UNKNOWN");
                    String userId   = (String) args.getOrDefault("userId", "all");

                    log.info("MCP: get_game_stats gameType={} userId={}", gameType, userId);

                    String result = String.format(
                            "Stats for %s (user: %s): TopScore=4200, Avg=1850, Games=47",
                            gameType, userId);

                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(result)))
                            .build();
                }
        );
    }
}