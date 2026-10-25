package com.xkondix.springai.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Service;

/**
 * Spring AI Orchestrator using MCP tools.
 *
 * Comparison with LangChain4j OrchestratorService:
 *   LangChain4j: McpToolProvider -> toolProvider() in AiServices
 *   Spring AI:   ToolCallbackProvider (autoconfigured) -> .tools(...)
 *
 * SPRING AI 2.0: toolCallbacks(...) is deprecated for removal in 3.0.
 * tools(Object...) takes ToolCallbacks, providers and @Tool objects alike.
 * The autoconfigured provider (SyncMcpToolCallbackProvider) caches the tool
 * list and refreshes it when a server sends tools/list_changed.
 *
 * Connected MCP servers (configured in application.yml, `streamable-http`):
 *   - java-mcp-server (port 8081) — game stats, knowledge base, weather
 *
 * There is only one. The repo's other MCP server, `claude-mcp-server`, speaks
 * STDIO and is launched by Claude Desktop, so no HTTP client can reach it —
 * an earlier version of this comment listed it on port 8086, which no longer
 * exists.
 *
 * ── THIS MODULE PROPAGATES THE TRACE CONTEXT; ITS TWIN DOES NOT ────────────
 *
 * `McpTracePropagationConfig` injects W3C `traceparent` into every MCP
 * request, so mcp-server's spans land inside this agent's trace and Tempo
 * reports `Services: 2`. `langchain4j-agent-mcp` deliberately does not, and
 * the same tool call there produces two unrelated traces.
 *
 * Same protocol, same transport, same tool, different picture — which is the
 * clearest way to say that context propagation is a client implementation
 * decision and not a property of MCP. See OBSERVABILITY.md.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiOrchestratorService {

    private final ChatClient chatClient;
    private final ToolCallbackProvider mcpToolCallbackProvider;

    public String orchestrate(String message) {
        log.info("Spring AI Orchestrator: {}", message);

        return chatClient.prompt()
                .system("""
                        You are an orchestrator agent.
                        You have access to tools from the java-mcp-server:
                        get_game_stats, save_note, search_notes, delete_note, get_weather.
                        Use the most appropriate tool for each task.
                        save_note and delete_note wait until a human approves or rejects them.
                        Report what the tool returned. Never ask the user to approve again.
                        """)
                .user(message)
                .advisors(new SimpleLoggerAdvisor())
                .tools(mcpToolCallbackProvider)
                .call()
                .content();
    }
}
