package com.xkondix.lc4j.mcp.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client configuration for LangChain4j.
 *
 * Two HTTP transports:
 *   1. Streamable HTTP -> mcp-server      (port 8081) — game stats, KB, weather
 *   2. Streamable HTTP -> code-mcp-server (port 8086) — project file access
 *
 * Both transports are transparent to the agent loop —
 * the agent just sees "tools" regardless of where they run.
 *
 * Note: stdio transport (Python subprocess) intentionally omitted
 * to keep the demo focused on Java MCP ecosystem.
 */
@Slf4j
@Configuration
public class McpClientConfig {

    /**
     * HTTP client — connects to mcp-server Spring Boot app (port 8081).
     * Tools: get_game_stats, save_note, search_notes, get_weather
     */
    @Bean(name = "javaMcpClient")
    public McpClient javaMcpClient() {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:8081/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("langchain4j-java-client")
                .clientVersion("1.0")
                .build();
    }

    /**
     * HTTP client — connects to code-mcp-server (port 8086).
     * Tools: read_file, list_files, get_project_structure,
     *        search_in_files, write_file, create_file, move_file, delete_file
     */
    @Bean(name = "codeMcpClient")
    public McpClient codeMcpClient() {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:8086/mcp")
                .logRequests(true)
                .logResponses(true)
                .build();

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("langchain4j-code-client")
                .clientVersion("1.0")
                .build();
    }
}
