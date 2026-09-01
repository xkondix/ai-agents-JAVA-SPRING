package com.xkondix.lc4j.mcp.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP Client configuration for LangChain4j.
 *
 * TRANSPORT MUST MATCH THE SERVER — and the answer flipped in Spring AI 2.0.
 *
 * The comment that used to live here said the opposite: it recorded that
 * StreamableHttpMcpTransport had been tried, the server answered 404, and the
 * fix was to go back to SSE. That was correct for Spring AI 1.x, where the
 * webmvc starter served GET /sse + POST /mcp/message.
 *
 * In 2.0 the server's ServerProtocol enum defaults to STREAMABLE, SSE is
 * @Deprecated(since = "2.0.0", forRemoval = true), and mcp-server now runs
 * protocol: STREAMABLE exposing a single endpoint at POST /mcp. So the old
 * 404 has swapped sides: SSE is now the transport that gets nothing back.
 *
 * Note the implementations differ under the hood, which is visible in stack
 * traces: HttpMcpTransport (SSE) is built on OkHttp, StreamableHttpMcpTransport
 * uses the JDK's java.net.http.HttpClient.
 *
 * FAILURE IS FATAL, NOT DEGRADED. DefaultMcpClient initialises eagerly inside
 * build(), so a wrong transport or a server that is not running does not leave
 * the agent toolless — it prevents this application from starting. mcp-server
 * (8081) must be up first.
 *
 * Comparison note for the talk: Spring AI configures the same thing in yml
 * (spring.ai.mcp.client.streamable-http.connections.*), LangChain4j does it
 * in code. Same protocol, same transport, two different places to get it wrong.
 */
@Slf4j
@Configuration
public class McpClientConfig {

    /**
     * Streamable HTTP client — connects to mcp-server (port 8081).
     * Tools: get_game_stats, save_note, search_notes, delete_note, get_weather
     *
     * The URL is the MCP endpoint itself (/mcp), not a base URL — unlike the
     * Spring AI client, which takes url + endpoint as separate properties.
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
     * Second MCP server — kept for the orchestrator demo (one agent, several
     * MCP servers) and DISABLED BY DEFAULT.
     *
     * claude-mcp-server is not a candidate for it: that module runs over STDIO
     * for Claude Desktop and is not reachable over HTTP at all. Point this at
     * any second Spring AI MCP server started with protocol: STREAMABLE.
     *
     * Left disabled because the eager initialisation above applies here too —
     * a dead port would stop the whole application from starting:
     *   lc4j:
     *     mcp:
     *       code-server:
     *         enabled: true
     */
    @Bean(name = "codeMcpClient")
    @ConditionalOnProperty(name = "lc4j.mcp.code-server.enabled", havingValue = "true")
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
