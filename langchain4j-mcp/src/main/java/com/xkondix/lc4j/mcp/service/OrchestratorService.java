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

@Slf4j
@Service
public class OrchestratorService {

    private interface OrchestratorAssistant {
        @SystemMessage("""
                You are an orchestrator agent.
                You have access to:
                - Java tools: game statistics, knowledge base, weather
                - Python tools: AI game analysis, strategy generation
                Use the most appropriate tool for each task.
                Always explain which tool you chose and why.
                """)
        String chat(@UserMessage String message);
    }

    private final OrchestratorAssistant assistant;

    public OrchestratorService(
            ChatModel model,
            @Qualifier("javaMcpClient")   McpClient javaMcpClient,
            @Qualifier("pythonMcpClient") McpClient pythonMcpClient) {

        McpToolProvider toolProvider = McpToolProvider.builder()
                .mcpClients(javaMcpClient, pythonMcpClient)
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