package com.xkondix.mcpserver.config;

import com.xkondix.mcpserver.tools.GameStatsTools;
import com.xkondix.mcpserver.tools.KnowledgeBaseTools;
import com.xkondix.mcpserver.tools.WeatherTools;
import io.modelcontextprotocol.server.McpServerFeatures;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

/**
 * Registers MCP tools exposed by this server.
 * Transport: HTTP/SSE  ->  GET /mcp/sse
 */
@Configuration
public class McpServerConfig {

    @Bean
    public List<McpServerFeatures.SyncToolSpecification> mcpTools(
            GameStatsTools gameStats,
            KnowledgeBaseTools knowledgeBase,
            WeatherTools weather) {
        return List.of(
                gameStats.getGameStatsTool(),
                knowledgeBase.getSaveNoteTool(),
                knowledgeBase.getSearchNotesTool(),
                weather.getWeatherTool()
        );
    }
}
