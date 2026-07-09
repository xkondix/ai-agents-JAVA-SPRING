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
 *   Spring AI:   ToolCallbackProvider (autoconfigured) -> .toolCallbacks(...)
 *
 * API pitfall worth remembering:
 *   .tools(Object...)          — for instances with @Tool-annotated methods
 *   .toolCallbacks(callbacks)  — for ready ToolCallback objects (our case)
 * Passing ToolCallbacks into .tools(...) silently registers NOTHING —
 * the model then answers from imagination instead of calling tools.
 *
 * Connected MCP servers (configured in application.yml):
 *   - java-mcp-server  (port 8081) — game stats, knowledge base, weather
 *   - code-mcp-server  (port 8086) — enable in yml when it runs over HTTP
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
                        For operations that modify data (save_note, delete_note),
                        always inform the user that human approval is required.
                        """)
                .user(message)
                .advisors(new SimpleLoggerAdvisor())
                .toolCallbacks(mcpToolCallbackProvider.getToolCallbacks())
                .call()
                .content();
    }
}
