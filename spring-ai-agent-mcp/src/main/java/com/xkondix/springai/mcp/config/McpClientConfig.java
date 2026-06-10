package com.xkondix.springai.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Spring AI MCP Client configuration.
 *
 * Spring AI auto-configures MCP clients from application.yml.
 * This config class shows the programmatic approach as well.
 *
 * For autoconfiguration: see application.yml spring.ai.mcp.client section.
 * The SyncMcpToolCallbackProvider converts MCP tools -> Spring AI ToolCallbacks.
 */
@Slf4j
@Configuration
public class McpClientConfig {

    /**
     * Expose MCP tools as Spring AI ToolCallback[]
     * so ChatClient can use them directly.
     *
     * Spring AI autoconfiguration injects SyncMcpToolCallbackProvider
     * when spring-ai-starter-mcp-client is on classpath.
     */
    @Bean
    public ToolCallback[] mcpToolCallbacks(
            SyncMcpToolCallbackProvider mcpToolCallbackProvider) {
        ToolCallback[] tools = mcpToolCallbackProvider.getToolCallbacks();
        log.info("MCP tools registered in Spring AI: {}", tools.length);
        for (ToolCallback tool : tools) {
            log.info("  - tool: {}", tool.getToolDefinition().name());
        }
        return tools;
    }
}
