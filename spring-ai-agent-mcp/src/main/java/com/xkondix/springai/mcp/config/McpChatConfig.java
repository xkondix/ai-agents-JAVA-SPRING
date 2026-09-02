package com.xkondix.springai.mcp.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * ChatClient for the orchestrator.
 *
 * Built from the AUTO-CONFIGURED ChatClient.Builder, not from
 * ChatClient.builder(chatModel): the static factory uses ObservationRegistry.NOOP
 * and drops every ChatClient-level observation, including the spring.ai.tool
 * span around each MCP tool execution. With the injected builder the trace
 * reads chat → tool_call (agent side) → http post /mcp → mcp_tool (server
 * side) → chat, i.e. the same shape as LangChain4j and raw-agent, plus the
 * server half that propagation adds. Full note in
 * patterns-spring-ai/config/AgentConfig.
 */
@Configuration
public class McpChatConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You are an orchestrator agent. " +
                        "Use available MCP tools when needed.")
                .build();
    }
}
