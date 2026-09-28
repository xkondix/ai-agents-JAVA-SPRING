package com.xkondix.claude.mcp.server.primitives;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Span + meters for the NON-TOOL MCP primitives: resources, prompts and
 * completions.
 *
 * ── WHY A SEPARATE CLASS AND NOT CodeToolsService.traced() ─────────────────
 *
 * That method is private and specific to tools: it measures request payload
 * size (a tool call carries arguments, sometimes a whole file body) and it
 * derives an outcome from the "ERROR: ..." convention that tool results use.
 * Neither applies here. A resource read carries a URI and returns content; a
 * prompt render carries a couple of short arguments and returns text the
 * CLIENT will use, not the model.
 *
 * Copying the wrapper a third time would have been the easy move — it is
 * already duplicated once, between this module and common/McpToolTelemetry,
 * for a documented reason (this module sits outside the reactor). A third
 * copy inside the SAME module would have had no reason at all.
 *
 * ── SEPARATE METER NAMES, ON PURPOSE ───────────────────────────────────────
 *
 * mcp.primitive.* rather than reusing mcp.tool.* with an extra tag. The
 * existing Grafana panels read mcp_tool_calls_total as "how often did the
 * model invoke a tool"; folding resource reads into that series would keep
 * every query valid and quietly change what every number means. A new name
 * is cheap; a metric that silently changes definition is the kind of thing
 * this project collects in OBSERVABILITY.md.
 *
 * ── SAME TAG SHAPE AS THE TOOLS, INCLUDING direction ───────────────────────
 *
 * payload.size carries direction="response" even though there is only ever
 * one direction here. A constant tag looks like waste, and the first version
 * left it out — until the series had to share a Grafana panel with
 * mcp_tool_payload_size_chars, which is split into request and response.
 *
 * Without the tag the panel would have had two differently shaped legends and
 * a title ("request vs response") that was true of half its series. The
 * alternative was renaming the panel to something vaguer, which trades a
 * one-word fix in the producer for a permanently less precise dashboard.
 *
 * Resources and prompts genuinely have no meaningful request payload: the
 * request is a URI or two short arguments, and measuring it would report a
 * number nobody can act on. So the direction is constant BECAUSE the
 * primitive is asymmetric, not because the tag is decorative — and a reader
 * comparing the two panels can see that asymmetry instead of guessing at it.
 *
 * ── SPAN KIND IS SERVER, FOR THE SAME REASON AS TOOLS ──────────────────────
 *
 * These spans handle an inbound request. Beyond semantics it is load-bearing:
 * Tempo's metrics generator builds the service graph and RED metrics from span
 * kind, and INTERNAL spans (what tracer.nextSpan() produces) leave this
 * service off the graph entirely.
 *
 * ── NO HISTOGRAM BUCKETS HERE ──────────────────────────────────────────────
 *
 * The MeterFilter that adds SLO buckets lives in `common`'s
 * ObservabilityAutoConfiguration, and this module does not depend on
 * `common`. So mcp_primitive_duration_milliseconds_bucket has only le="+Inf"
 * and histogram_quantile() over it returns nothing — a p95 panel would sit
 * empty and look broken.
 *
 * Left as is on purpose: these operations are a string concatenation and a
 * file read, measured in single-digit milliseconds. A percentile over that
 * distribution is noise. Use avg from sum/count if anyone asks.
 *
 * Tracer is nullable and MeterRegistry falls back to a no-op, exactly as in
 * CodeToolsService: a file server must not stop serving because telemetry is
 * switched off.
 */
@Slf4j
@Component
public class McpPrimitiveTelemetry {

    /** Same value as the tools and the agent modules — panels slice by it. */
    private static final String FRAMEWORK = "spring-ai";

    /**
     * The only direction these primitives have. Mirrors the tool meters, whose
     * values are request|response, so both can share one panel — see the class
     * comment.
     */
    private static final String RESPONSE = "response";

    private final @Nullable Tracer tracer;
    private final MeterRegistry meterRegistry;

    public McpPrimitiveTelemetry(ObjectProvider<Tracer> tracerProvider,
                                 ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.tracer = tracerProvider.getIfAvailable();
        MeterRegistry registry = meterRegistryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("No MeterRegistry bean available — mcp_primitive_* metrics will NOT be "
                    + "exported (in-memory SimpleMeterRegistry fallback)");
            registry = new SimpleMeterRegistry();
        }
        this.meterRegistry = registry;
    }

    /** Like Supplier, but allowed to throw — file access declares IOException. */
    @FunctionalInterface
    public interface Operation {
        String execute() throws Exception;
    }

    /**
     * @param type "resource", "prompt" or "completion"
     * @param name the resource URI, prompt name or completion target
     */
    public String traced(String type, String name, Operation operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result;

        if (tracer == null) {
            log.info("[{}] {}", type.toUpperCase(), name);
            result = execute(type, name, operation);
        } else {
            Span span = tracer.spanBuilder()
                    .name("mcp_" + type + " " + name)
                    .kind(Span.Kind.SERVER)
                    .tag("mcp.primitive.type", type)
                    .tag("mcp.primitive.name", name)
                    .tag("framework", FRAMEWORK)
                    .start();
            // The log line goes INSIDE the scope. Outside it, the MDC has no
            // trace id yet and the line becomes an orphan in Loki — one per
            // call, invisible when filtering by trace. Same lesson as the
            // tools wrapper.
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                log.info("[{}] {}", type.toUpperCase(), name);
                result = execute(type, name, operation);
                span.tag("mcp.primitive.response.length", String.valueOf(result.length()));
            } finally {
                span.end();
            }
        }

        record(type, name, result, sample);
        return result;
    }

    /**
     * Outcome is derived from the returned text, not from an exception,
     * because failures are returned rather than thrown — the client gets
     * something readable instead of a protocol error. Counting only thrown
     * exceptions would report a permanent 0% error rate.
     */
    private void record(String type, String name, String result, Timer.Sample sample) {
        String outcome = result.startsWith("ERROR:") ? "error" : "success";

        sample.stop(Timer.builder("mcp.primitive.duration")
                .description("Duration of an MCP resource/prompt/completion request")
                .tag("primitive", type)
                .tag("name", name)
                .tag("outcome", outcome)
                .tag("framework", FRAMEWORK)
                .register(meterRegistry));

        meterRegistry.counter("mcp.primitive.calls",
                "primitive", type,
                "name", name,
                "outcome", outcome,
                "framework", FRAMEWORK).increment();

        DistributionSummary.builder("mcp.primitive.payload.size")
                .description("Characters returned by an MCP resource or prompt")
                .baseUnit("chars")
                .tag("primitive", type)
                .tag("name", name)
                .tag("direction", RESPONSE)
                .tag("framework", FRAMEWORK)
                .register(meterRegistry)
                .record(result.length());
    }

    private String execute(String type, String name, Operation operation) {
        try {
            return operation.execute();
        } catch (Exception e) {
            log.error("[{}] {} failed: {}", type.toUpperCase(), name, e.getMessage());
            Span current = (tracer != null) ? tracer.currentSpan() : null;
            if (current != null) {
                current.tag("error.type", e.getClass().getSimpleName());
                current.error(e);
            }
            return "ERROR: " + e.getMessage();
        }
    }
}
