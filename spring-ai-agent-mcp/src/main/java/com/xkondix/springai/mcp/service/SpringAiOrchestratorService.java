package com.xkondix.springai.mcp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/**
 * Spring AI Orchestrator using MCP tools.
 *
 * Comparison with LangChain4j OrchestratorService:
 *   LangChain4j: McpToolProvider -> toolProvider() in AiServices
 *   Spring AI:   SyncMcpToolCallbackProvider -> ToolCallback[] -> .tools(callbacks)
 *
 * Spring AI approach is more explicit — easier to inspect registered tools.
 * MCP clients are autoconfigured from application.yml (no manual @Bean needed).
 *
 * Connected MCP servers (configured in application.yml):
 *   - java-mcp-server  (port 8081) — game stats, knowledge base, weather
 *   - code-mcp-server  (port 8086) — project file access and editing
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
                        You have access to tools from two MCP servers:
                        - java-mcp-server tools: get_game_stats, save_note,
                                                  search_notes, get_weather
                        - code-mcp-server tools: read_file, list_files,
                                                  get_project_structure, search_in_files,
                                                  write_file, create_file, move_file, delete_file
                        Use the most appropriate tool for each task.
                        For file operations that modify data, always inform the user
                        that human approval is required.
                        """)
                .user(message)
                .advisors(new SimpleLoggerAdvisor())
                .tools(mcpToolCallbacks)
                .call()
                .content();
    }
}
