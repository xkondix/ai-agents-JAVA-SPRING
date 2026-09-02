package com.xkondix.common.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import java.time.Duration;
import java.util.Set;

/**
 * Shared observability tuning for ALL modules (loaded via
 * META-INF/spring/...AutoConfiguration.imports — no component scan needed,
 * which is why this works even though each app has a different base package).
 *
 * Two concerns live here:
 *
 * 1. ACTUATOR NOISE FILTER. The chat-ui polls /actuator/health of every agent
 *    every few seconds. With sampling.probability=1.0 each poll becomes a full
 *    trace, flooding Grafana Tempo and burying the interesting chat traces. It
 *    also inflates http.server.requests metrics in Prometheus. The
 *    ObservationPredicate rejects observations for actuator requests BEFORE
 *    they are created, so neither spans nor http.server.requests metrics are
 *    produced for them.
 *
 * 2. HISTOGRAM BUCKETS FOR THE LATENCY TIMERS. Micrometer exports a Timer to
 *    OTLP as a histogram, but WITHOUT explicit buckets unless the timer is
 *    configured with some — and none of ours were: the OTLP exporter then
 *    ships only sum, count and a single +Inf bucket. In Prometheus that shows
 *    up as gen_ai_client_operation_milliseconds_bucket{le="+Inf"} and nothing
 *    else, and histogram_quantile() needs at least two bounds to compute
 *    anything, so the "p95 by module" panel stayed empty while the avg panel
 *    next to it worked. Verified 2026-09-02 with
 *    {__name__=~"gen_ai_client_operation_milliseconds.*"}.
 *
 *    The MeterFilter below adds fixed SLO bounds to the latency timers of the
 *    project. It is a MeterFilter bean, so Spring Boot applies it to the
 *    auto-configured registry before any meter is created, which covers
 *    every producer at once:
 *      - raw-agent (LlmClient, Timer.builder by hand),
 *      - the LangChain4j listener in this module (Timer.builder by hand),
 *      - Spring AI (timers created by its ObservationHandler — the only
 *        place where a builder-side .serviceLevelObjectives() is NOT an
 *        option, and the reason this is a filter and not eight edits),
 *      - mcp-server (McpToolTelemetry).
 *    claude-mcp-server does not depend on `common` and keeps no buckets;
 *    its panels use avg only.
 *
 *    ── TWO SPELLINGS OF THE SAME METER ───────────────────────────────────
 *    Spring AI names its chat timer `gen_ai.client.operation` (underscore
 *    after gen, as in the OTel GenAI semantic conventions). raw-agent and the
 *    LC4j listener name theirs `gen.ai.client.operation` (all dots — the
 *    Micrometer convention). Prometheus normalises BOTH to
 *    gen_ai_client_operation, so every dashboard panel works for all three
 *    frameworks and the difference is invisible there. A MeterFilter matches
 *    at the Micrometer level, where they are two different names: the first
 *    version of this filter listed only the dotted one, and after a restart
 *    the buckets appeared for raw-agent and the four LC4j modules and NOT for
 *    the three Spring AI modules. Both spellings are listed now. (Renaming our
 *    own timers to the Spring AI spelling would be the cleaner fix, but it
 *    would change the meter identity mid-project for no gain on the panels.)
 *
 *    FIXED BOUNDS, NOT publishPercentileHistogram(). Percentile histograms
 *    emit dozens of buckets per series; eight bounds are enough for a demo
 *    where two or three calls per minute make p95 "the slowest call" anyway,
 *    and the cardinality stays readable in Prometheus. The bounds are chosen
 *    for LLM latency (hundreds of ms to tens of seconds) and are reused for
 *    tool timers, where most values fall in the first bucket and the
 *    approval-gated ones in the last — which is exactly the contrast the
 *    human-in-the-loop panel is about.
 *
 * What neither part affects:
 *   - custom Counters registered directly on MeterRegistry
 *   - manually created spans (Tracer API)
 *   - gen_ai.* observations from Spring AI (different context type) — the
 *     predicate ignores them; the filter only touches their TIMERS
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ObservationPredicate.class, ServerRequestObservationContext.class, MeterFilter.class})
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    /**
     * Timers that get histogram buckets; matched by name prefix at the
     * MICROMETER level — hence both spellings of the chat timer.
     */
    private static final Set<String> LATENCY_TIMERS = Set.of(
            "gen.ai.client.operation",   // raw-agent, LangChain4j listener
            "gen_ai.client.operation",   // Spring AI ChatModel observation
            "spring.ai.tool",            // Spring AI tool execution observation
            "mcp.tool.duration");        // McpToolTelemetry (mcp-server)

    /** Bucket upper bounds. Timers store nanoseconds, so Duration is the unit here. */
    private static final Duration[] LATENCY_BOUNDS = {
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5),
            Duration.ofSeconds(10),
            Duration.ofSeconds(30),
            Duration.ofSeconds(60)
    };

    /**
     * Drop HTTP server observations for configured path prefixes
     * (default: /actuator). Returning false here means: no span,
     * no http.server.requests metric for this request.
     */
    @Bean
    ObservationPredicate excludedPathsObservationPredicate(ObservabilityProperties props) {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                String uri = serverContext.getCarrier().getRequestURI();
                return props.excludedPathPrefixes().stream().noneMatch(uri::startsWith);
            }
            return true;
        };
    }

    /**
     * Histogram buckets for the latency timers — see the class comment.
     * merge(config) keeps whatever the producer already configured and only
     * fills in what is missing, so a future builder-side setting wins.
     */
    @Bean
    MeterFilter latencyHistogramBuckets() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (id.getType() != Meter.Type.TIMER || !isLatencyTimer(id.getName())) {
                    return config;
                }
                double[] boundsNanos = new double[LATENCY_BOUNDS.length];
                for (int i = 0; i < LATENCY_BOUNDS.length; i++) {
                    boundsNanos[i] = LATENCY_BOUNDS[i].toNanos();
                }
                return DistributionStatisticConfig.builder()
                        .serviceLevelObjectives(boundsNanos)
                        .build()
                        .merge(config);
            }
        };
    }

    private static boolean isLatencyTimer(String name) {
        return LATENCY_TIMERS.stream().anyMatch(name::startsWith);
    }
}
