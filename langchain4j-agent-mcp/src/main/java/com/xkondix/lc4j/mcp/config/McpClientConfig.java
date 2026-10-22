package com.xkondix.lc4j.mcp.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

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
     *
     * TWO BEAN METHODS, ONE BEAN. Exactly one of them is registered under the
     * name "javaMcpClient", chosen by lc4j.mcp.trace-propagation.enabled:
     *   false (default) — no traceparent, a tool call produces two traces, the
     *                     contrast with spring-ai-agent-mcp the talk is built on
     *   true            — traceparent on every POST /mcp, one trace, Services: 2
     *
     * The Java method names differ ON PURPOSE. Spring remembers a condition that
     * skipped a @Bean method by its METHOD name, so two overloads both called
     * javaMcpClient would both be skipped as soon as the first one is
     * (ConfigurationClassBeanDefinitionReader, skippedBeanMethods).
     */
    @Bean(name = "javaMcpClient")
    @ConditionalOnProperty(name = "lc4j.mcp.trace-propagation.enabled", havingValue = "false",
            matchIfMissing = true)
    public McpClient javaMcpClient() {
        log.info("MCP trace propagation disabled — a tool call produces two traces");
        return client(transport().build());
    }

    /**
     * Same client, plus one header supplier. Unlike the two-step pattern in
     * spring-ai-agent-mcp, one hook is enough: customHeaders(Supplier) runs
     * synchronously while the request is built, on the thread that executes the
     * tool, where TracingToolProvider has already opened the
     * "execute_tool <name>" span (langchain4j-mcp 1.16.3,
     * StreamableHttpMcpTransport.createRequest). That stops being true if
     * AiServices is ever set to run tools concurrently on an executor — then
     * the context has to be captured first.
     *
     * Tracer and Propagator are required here: switching propagation on in a
     * context without tracing fails at startup instead of silently doing nothing.
     */
    @Bean(name = "javaMcpClient")
    @ConditionalOnProperty(name = "lc4j.mcp.trace-propagation.enabled", havingValue = "true")
    public McpClient javaMcpClientWithTracePropagation(Tracer tracer, Propagator propagator) {
        log.info("MCP trace propagation ENABLED — traceparent is sent on every POST /mcp");
        return client(transport()
                .customHeaders(() -> traceHeaders(tracer, propagator))
                .build());
    }

    private static StreamableHttpMcpTransport.Builder transport() {
        return new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:8081/mcp")
                .logRequests(true)
                .logResponses(true);
    }

    private static McpClient client(McpTransport transport) {
        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("langchain4j-java-client")
                .clientVersion("1.0")
                .build();
    }

    /**
     * W3C headers of the current span. Empty outside a request: initialize and
     * the client's health-check pings run with no span in scope.
     */
    private static Map<String, String> traceHeaders(Tracer tracer, Propagator propagator) {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return Map.of();
        }
        Map<String, String> headers = new HashMap<>();
        propagator.inject(context, headers, (map, key, value) -> map.put(key, value));
        return headers;
    }
}
