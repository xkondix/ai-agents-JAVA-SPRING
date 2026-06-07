package com.xkondix.springai.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Spring AI Orchestrator using MCP tools.
 *
 * Comparison with LangChain4j OrchestratorService:
 *
 *   LangChain4j:
 *     McpToolProvider -> toolProvider(toolProvider) in AiServices
 *
 *   Spring AI:
 *     SyncMcpToolCallbackProvider -> ToolCallback[] -> .tools(callbacks)
 *     More explicit, easier to inspect what tools are available.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiOrchestratorService {

    private final ChatClient chatClient;
    private final ToolCallback[] mcpToolCallbacks;

    public String orchestrate(String message) {
        log.info("Spring AI Orchestrator: {}", message);

        return chatClient.prompt()
                .system("""
                        You are an orchestrator agent.
                        Use available MCP tools when needed.
                        """)
                .user(message)
                .tools(mcpToolCallbacks)
                .call()
                .content();
    }
}
