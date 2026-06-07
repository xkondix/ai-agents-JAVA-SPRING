package com.xkondix.codemcp.config;

import com.xkondix.codemcp.tools.CodeToolsService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers @Tool methods from CodeToolsService as MCP tools.
 * Identical pattern as in the official Spring AI MCP documentation.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider codeTools(CodeToolsService codeToolsService) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(codeToolsService)
                .build();
    }
}
