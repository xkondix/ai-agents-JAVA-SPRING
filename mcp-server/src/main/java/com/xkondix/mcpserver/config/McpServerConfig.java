package com.xkondix.mcpserver.config;

import com.xkondix.mcpserver.tools.GameStatsTools;
import com.xkondix.mcpserver.tools.KnowledgeBaseTools;
import com.xkondix.mcpserver.tools.WeatherTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers MCP tools exposed by this server.
 *
 * Spring AI @Tool approach:
 *   - MethodToolCallbackProvider scans @Tool methods from provided objects
 *   - Generates JSON Schema automatically from method parameters
 *   - Registers them with the MCP server on startup
 *
 * Transport configured in application.yml:
 *   spring.ai.mcp.server.transport: SYNC_HTTP_SSE (for LangChain4j/Spring AI clients)
 */
@Configuration
public class McpServerConfig {

    @Bean
    public ToolCallbackProvider mcpTools(
            GameStatsTools gameStats,
            KnowledgeBaseTools knowledgeBase,
            WeatherTools weather) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(gameStats, knowledgeBase, weather)
                .build();
    }
}
