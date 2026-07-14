package com.xkondix.lc4j.mcp.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.HttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client configuration for LangChain4j.
 *
 * TRANSPORT MUST MATCH THE SERVER (lesson learned the 404 way):
 * both our servers run Spring AI MCP in SYNC_HTTP_SSE mode, which exposes
 * GET /sse + POST /mcp/message. The previous config used
 * StreamableHttpMcpTransport (single-endpoint protocol, POST /mcp) —
 * the server answered 404 and client initialization killed the whole
 * application context at startup (DefaultMcpClient initializes eagerly
 * inside build()).
 *
 * Comparison note for the talk: Spring AI configures the same thing in yml
 * (spring.ai.mcp.client.sse.connections.*), LangChain4j does it in code.
 */
@Slf4j
@Configuration
public class McpClientConfig {

    /**
     * SSE client — connects to mcp-server (port 8081).
     * Tools: get_game_stats, save_note, search_notes, delete_note, get_weather
     */
    @Bean(name = "javaMcpClient")
    public McpClient javaMcpClient() {
        McpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl("http://localhost:8081/sse")
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
     * SSE client — connects to code-mcp-server (port 8086).
     * DISABLED BY DEFAULT: code-mcp-server normally runs in STDIO mode for
     * Claude Desktop, so port 8086 is dead and eager client initialization
     * would prevent this application from starting at all.
     *
     * Enable only when code-mcp-server runs with transport=SYNC_HTTP_SSE:
     *   lc4j:
     *     mcp:
     *       code-server:
     *         enabled: true
     */
    @Bean(name = "codeMcpClient")
    @ConditionalOnProperty(name = "lc4j.mcp.code-server.enabled", havingValue = "true")
    public McpClient codeMcpClient() {
        McpTransport transport = new HttpMcpTransport.Builder()
                .sseUrl("http://localhost:8086/sse")
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
