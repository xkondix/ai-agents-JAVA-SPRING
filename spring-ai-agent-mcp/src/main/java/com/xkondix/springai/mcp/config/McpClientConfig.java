package com.xkondix.springai.mcp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI MCP Client configuration.
 *
 * MCP clients and the ToolCallbackProvider are AUTOCONFIGURED from
 * application.yml (spring.ai.mcp.client.streamable-http.connections.*).
 *
 * LESSON LEARNED (this cost us an evening):
 * A previous version declared its own bean named "mcpToolCallbacks"
 * (type ToolCallback[]). Spring AI's McpToolCallbackAutoConfiguration
 * ALSO defines a bean with that exact name (type SyncMcpToolCallbackProvider).
 * With allow-bean-definition-overriding=true the autoconfigured bean silently
 * REPLACED ours — the startup line "Overriding bean definition for bean
 * 'mcpToolCallbacks'" was the only symptom, and the model quietly received
 * zero tools (input_tokens in the trace exposed it: too small to contain
 * tool definitions).
 *
 * So: no competing bean here, and the overriding flag is gone from
 * application.yml — a future collision now fails the context at startup
 * instead of logging one INFO line. We only log what the autoconfiguration
 * discovered, so a broken MCP connection is visible at startup.
 */
@Slf4j
@Configuration
public class McpClientConfig {

    @Bean
    ApplicationRunner mcpToolsStartupLogger(ToolCallbackProvider mcpToolCallbackProvider) {
        return args -> {
            ToolCallback[] tools = mcpToolCallbackProvider.getToolCallbacks();
            log.info("MCP tools registered in Spring AI: {}", tools.length);
            for (ToolCallback tool : tools) {
                log.info("  - tool: {}", tool.getToolDefinition().name());
            }
            if (tools.length == 0) {
                log.warn("ZERO MCP tools discovered — check that mcp-server (8081) is up "
                        + "and spring.ai.mcp.client.streamable-http.connections is correct!");
            }
        };
    }
}
