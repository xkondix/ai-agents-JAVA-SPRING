package com.xkondix.common.observability;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Supplier;

/**
 * One span and three meters around a single MCP tool invocation — the
 * SERVER-side counterpart of the "tool_call" spans the agents emit.
 *
 * WHY THIS EXISTS. Spring AI 2.0 instruments tool calling on the AGENT side
 * (spring.ai.tool observations, see the Observability reference) but the
 * reference lists no observations for the MCP SERVER. Over Streamable HTTP
 * every JSON-RPC request is a POST /mcp, so without this wrapper the only
 * server span in a propagated trace is `http post /mcp` — the tool name is
 * visible in the log line and nowhere else. Tempo would show "Services: 2"
 * with an empty second service.
 *
 * Shared by both MCP servers so their telemetry has ONE shape:
 *   - mcp-server        (reactor, HTTP, called by the agents)
 *   - claude-mcp-server (standalone, STDIO, called by Claude Desktop) — that
 *     module has its own copy of this logic because it does not depend on
 *     `common`; keep the names below in sync with CodeToolsService.
 *
 * SPAN: kind SERVER, name "mcp_tool &lt;name&gt;". Kind matters: Tempo's
 * metrics generator builds the service graph and RED metrics from span kind,
 * and an INTERNAL span never becomes a node.
 *
 * METERS (Prometheus names after the OTLP exporter, base unit chars/ms):
 *   mcp.tool.calls        counter  {tool, outcome, framework}   -> mcp_tool_calls_total
 *   mcp.tool.duration     timer    {tool, outcome, framework}   -> mcp_tool_duration_milliseconds_*
 *   mcp.tool.payload.size summary  {tool, direction, framework} -> mcp_tool_payload_size_chars_*
 *
 * OUTCOME is derived from the RESULT TEXT ("ERROR: ..." prefix), because
 * tools return readable errors to the model instead of throwing.
 *
 * The `framework` tag is the instrumentation family (spring-ai), the same
 * value the Spring AI agent modules carry. Which PROCESS produced the series
 * is already answered by service.name / job — the tag does not need to repeat
 * it.
 *
 * Tracer and MeterRegistry are nullable on purpose: a tool must never fail
 * because telemetry is unavailable. With no Tracer the call is still timed
 * and counted; with no registry meters go to a throwaway SimpleMeterRegistry
 * and a WARN says so (that fallback is silent otherwise, and "silent" is what
 * this project collects as war stories — not what it wants on stage).
 */
public class McpToolTelemetry {

    private static final Logger log = LoggerFactory.getLogger(McpToolTelemetry.class);

    public static final String FRAMEWORK_SPRING_AI = "spring-ai";
    private static final String ERROR_PREFIX = "ERROR:";

    private final Tracer tracer;           // nullable
    private final MeterRegistry registry;  // never null
    private final String framework;

    public McpToolTelemetry(Tracer tracer, MeterRegistry registry, String framework) {
        this.tracer = tracer;
        this.framework = framework;
        if (registry == null) {
            log.warn("[OBSERVABILITY] No MeterRegistry — mcp_tool_* metrics will NOT be exported "
                    + "(falling back to an in-memory SimpleMeterRegistry)");
            this.registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        } else {
            this.registry = registry;
        }
        if (tracer == null) {
            log.warn("[OBSERVABILITY] No Tracer — mcp_tool spans are DISABLED, tools still work");
        }
    }

    /**
     * Wraps one tool call. Always returns the tool's result; never throws
     * because of telemetry. A RuntimeException from the tool itself is
     * recorded on the span and rethrown.
     *
     * @param toolName     wire name of the tool (the @McpTool name)
     * @param argsSummary  short, human-readable argument summary for the span
     * @param requestChars size of the incoming arguments, in characters
     */
    public String traced(String toolName, String argsSummary, int requestChars,
                         Supplier<String> operation) {
        Timer.Sample sample = Timer.start(registry);
        String result;

        if (tracer == null) {
            result = operation.get();
        } else {
            Span span = tracer.spanBuilder()
                    .name("mcp_tool " + toolName)
                    .kind(Span.Kind.SERVER)
                    .tag("gen_ai.operation.name", "execute_tool")
                    .tag("gen_ai.tool.name", toolName)
                    .tag("mcp.tool.args", String.valueOf(argsSummary))
                    .tag("mcp.tool.request.length", String.valueOf(requestChars))
                    .tag("framework", framework)
                    .start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                result = operation.get();
                span.tag("mcp.tool.response.length", String.valueOf(result.length()));
                if (result.startsWith(ERROR_PREFIX)) {
                    span.tag("error.type", "tool_error");
                }
            } catch (RuntimeException e) {
                span.error(e);
                record(toolName, requestChars, ERROR_PREFIX + " " + e.getMessage(), sample);
                throw e;
            } finally {
                span.end();
            }
        }

        record(toolName, requestChars, result, sample);
        return result;
    }

    private void record(String toolName, int requestChars, String result, Timer.Sample sample) {
        String outcome = result.startsWith(ERROR_PREFIX) ? "error" : "success";

        sample.stop(Timer.builder("mcp.tool.duration")
                .description("Duration of an MCP tool invocation")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .tag("framework", framework)
                .register(registry));

        registry.counter("mcp.tool.calls",
                "tool", toolName,
                "outcome", outcome,
                "framework", framework).increment();

        payloadSize(toolName, "request").record(requestChars);
        payloadSize(toolName, "response").record(result.length());
    }

    private DistributionSummary payloadSize(String toolName, String direction) {
        return DistributionSummary.builder("mcp.tool.payload.size")
                .description("Characters exchanged with the model by an MCP tool")
                .baseUnit("chars")
                .tag("tool", toolName)
                .tag("direction", direction)
                .tag("framework", framework)
                .register(registry);
    }

    public static int len(String value) {
        return value == null ? 0 : value.length();
    }
}
