package com.xkondix.lc4j.mcp.service;

import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Orchestrator Agent — uses tools from two MCP servers.
 *
 * The agent does not know (or care) that tools run in different processes:
 *   - get_game_stats, save_note, get_weather  → mcp-server      (port 8081)
 *   - read_file, write_file, search_in_files  → code-mcp-server (port 8086)
 *
 * It just sees a flat list of tools and picks the right one.
 * This is the core MCP orchestrator demo for Presentation 2.
 */
@Slf4j
@Service
public class OrchestratorService {

    private interface OrchestratorAssistant {
        @SystemMessage("""
                You are an orchestrator agent.
                You have access to tools from two MCP servers:
                - mcp-server tools:      get_game_stats, save_note, search_notes, get_weather
                - code-mcp-server tools: read_file, list_files, get_project_structure,
                                         search_in_files, write_file, create_file,
                                         move_file, delete_file
                Use the most appropriate tool for each task.
                Always explain which tool you chose and why.
                For file operations that modify data, always wait for human approval.
                """)
        String chat(@UserMessage String message);
    }

    private final OrchestratorAssistant assistant;

    public OrchestratorService(
            ChatModel model,
            @Qualifier("javaMcpClient") McpClient javaMcpClient,
            @Qualifier("codeMcpClient") McpClient codeMcpClient) {

        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(javaMcpClient, codeMcpClient)
                .build();

        this.assistant = AiServices.builder(OrchestratorAssistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .chatMemory(MessageWindowChatMemory.withMaxMessages(30))
                .build();
    }

    public String orchestrate(String message) {
        log.info("Orchestrator received: {}", message);
        return assistant.chat(message);
    }
}
