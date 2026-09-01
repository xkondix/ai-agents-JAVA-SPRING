package com.xkondix.springai.mcp.config;

import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.common.McpTransportContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.customizer.McpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Joins agent and mcp-server traces into ONE distributed trace.
 *
 * Problem: the MCP client transport uses a plain java.net.http.HttpClient
 * that Micrometer does not instrument — no client span, no W3C traceparent
 * header, so mcp-server starts a brand-new trace for every tool call.
 *
 * Why a naive request customizer is NOT enough (verified empirically):
 * the transport sends its POSTs on internal worker threads, while
 * Micrometer's trace context lives in a THREAD-LOCAL of the calling (Tomcat)
 * thread. On the worker thread currentTraceContext() is null.
 *
 * The SDK's answer is the two-step transport-context pattern (same one
 * mcp-security uses to carry Authentication across threads):
 *
 *  1. transportContextProvider — invoked on the CALLER thread when a
 *     request starts. We capture the trace context from the thread-local
 *     into an immutable McpTransportContext (a per-request bag of values).
 *  2. McpSyncHttpClientRequestCustomizer — invoked on WHATEVER thread the
 *     transport uses. It cannot see thread-locals, but it receives the
 *     McpTransportContext as a parameter — we copy the captured headers
 *     onto the outgoing HTTP request.
 *
 * Server side needs nothing: Spring MVC extracts traceparent from
 * incoming requests automatically, so mcp-server spans become children
 * of the agent's tool span.
 *
 * ── SPRING AI 2.0: HOW THE CUSTOMIZERS ARE DISCOVERED CHANGED ──────────────
 *
 * Two things moved, and only the first one is obvious.
 *
 * (a) McpSyncClientCustomizer / McpAsyncClientCustomizer were replaced by one
 *     generic interface, McpClientCustomizer&lt;B&gt;, where the sync/async
 *     distinction lives in the type parameter. That failed loudly at compile
 *     time.
 *
 * (b) A STANDALONE McpSyncHttpClientRequestCustomizer BEAN IS NO LONGER
 *     PICKED UP. Verified in StreamableHttpHttpClientTransportAutoConfiguration
 *     (and identically in the SSE one): the only thing either of them injects
 *     is ObjectProvider&lt;McpClientCustomizer&lt;TransportBuilder&gt;&gt;. Nothing reads a
 *     bare request-customizer bean any more. So the customizer must now be
 *     attached to the TRANSPORT BUILDER, via .httpRequestCustomizer(...).
 *
 * (b) failed in complete silence. The context started, the bean existed, step
 * 1 logged "Captured trace context" on every call — and the header was never
 * written. The only way to see it was to compare trace ids on both sides:
 *
 *     agent   14:22:25.605  [23c3b29e...]  Captured trace context ...
 *     server  14:22:25.626  [4b46f917...]  [MCP] get_game_stats gameType=SNAKE
 *
 * Different ids, 21 ms apart, same call. Tempo showed Services: 1 and the
 * demo looked broken while every individual piece worked.
 *
 * THE GENERIC ARGUMENT IS LOAD-BEARING. McpClientCustomizer&lt;HttpClientStreamableHttpTransport.Builder&gt;
 * is matched by type; an unparameterised McpClientCustomizer compiles and is
 * then never applied. Same failure mode as above, one level down.
 */
@Slf4j
@Configuration
public class McpTracePropagationConfig {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACESTATE = "tracestate";

    /**
     * Step 1 — capture (caller thread, thread-local available).
     * Serializes the current trace context into W3C headers using the same
     * Propagator that instrumented RestClients use, and stashes them in the
     * per-request McpTransportContext.
     */
    @Bean
    McpClientCustomizer<McpClient.SyncSpec> traceContextCapturingMcpCustomizer(
            Tracer tracer, Propagator propagator) {

        return (name, spec) -> spec.transportContextProvider(() -> {
            var traceContext = tracer.currentTraceContext().context();
            if (traceContext == null) {
                // e.g. client initialization on the main thread — nothing to carry
                return McpTransportContext.EMPTY;
            }
            Map<String, Object> headers = new HashMap<>();
            propagator.inject(traceContext, headers, (map, key, value) -> map.put(key, value));
            log.trace("Captured trace context for MCP connection '{}': {}", name, headers.keySet());
            return McpTransportContext.create(headers);
        });
    }

    /**
     * Step 2 — inject, attached to the TRANSPORT BUILDER.
     *
     * This is the part that changed in Spring AI 2.0: the request customizer is
     * no longer discovered as a top-level bean, so it is registered here
     * through a transport-builder customizer instead.
     */
    @Bean
    McpClientCustomizer<HttpClientStreamableHttpTransport.Builder> traceContextPropagatingMcpCustomizer(
            Tracer tracer, Propagator propagator) {

        return (name, transportBuilder) ->
                transportBuilder.httpRequestCustomizer(requestCustomizer(tracer, propagator));
    }

    /**
     * Runs on the transport worker thread, where the thread-local is NOT
     * available. Prefers the thread-local if it happens to be present,
     * otherwise falls back to the headers captured in step 1.
     */
    private McpSyncHttpClientRequestCustomizer requestCustomizer(Tracer tracer, Propagator propagator) {
        return (builder, method, endpoint, body, context) -> {
            var traceContext = tracer.currentTraceContext().context();
            if (traceContext != null) {
                propagator.inject(traceContext, builder, (b, key, value) -> b.header(key, value));
                log.trace("Injected trace context (thread-local) into MCP request {} {}", method, endpoint);
                return;
            }
            if (context != null && context.get(TRACEPARENT) instanceof String traceparent) {
                builder.header(TRACEPARENT, traceparent);
                if (context.get(TRACESTATE) instanceof String tracestate) {
                    builder.header(TRACESTATE, tracestate);
                }
                log.trace("Injected trace context (transport context) into MCP request {} {}", method, endpoint);
                return;
            }
            log.trace("No trace context available for MCP request {} {}", method, endpoint);
        };
    }
}
