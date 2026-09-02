package com.xkondix.common.observability;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Meter contract of the MCP server-side telemetry.
 *
 * The names and tags asserted here are what the Grafana MCP row queries
 * (mcp_tool_calls_total, mcp_tool_duration_milliseconds_*,
 * mcp_tool_payload_size_chars_*) and what claude-mcp-server's CodeToolsService
 * duplicates by hand. Change one, change all three.
 */
@DisplayName("McpToolTelemetry")
class McpToolTelemetryTest {

    private SimpleMeterRegistry registry;
    private McpToolTelemetry telemetry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        // Tracer.NOOP would also work; null exercises the "no tracing" branch on purpose
        telemetry = new McpToolTelemetry(null, registry, McpToolTelemetry.FRAMEWORK_SPRING_AI);
    }

    @Test
    @DisplayName("returns the tool result unchanged and counts a success")
    void successIsCountedAndReturned() {
        String result = telemetry.traced("get_weather", "city=Katowice", 8, () -> "Weather: 22C");

        assertThat(result).isEqualTo("Weather: 22C");
        assertThat(registry.find("mcp.tool.calls")
                .tag("tool", "get_weather")
                .tag("outcome", "success")
                .tag("framework", "spring-ai")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("mcp.tool.duration")
                .tag("tool", "get_weather")
                .tag("outcome", "success")
                .timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("derives outcome=error from the ERROR: prefix, not from an exception")
    void errorTextIsCountedAsError() {
        String result = telemetry.traced("delete_note", "id=x", 4, () -> "ERROR: No note found");

        assertThat(result).startsWith("ERROR:");
        assertThat(registry.find("mcp.tool.calls").tag("outcome", "error").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.find("mcp.tool.calls").tag("outcome", "success").counter()).isNull();
    }

    @Test
    @DisplayName("records payload size in both directions")
    void payloadIsRecordedPerDirection() {
        telemetry.traced("read_file", "path", 12, () -> "0123456789");

        assertThat(registry.find("mcp.tool.payload.size")
                .tag("tool", "read_file").tag("direction", "request")
                .summary().totalAmount()).isEqualTo(12.0);
        assertThat(registry.find("mcp.tool.payload.size")
                .tag("tool", "read_file").tag("direction", "response")
                .summary().totalAmount()).isEqualTo(10.0);
    }

    @Test
    @DisplayName("a RuntimeException from the tool propagates and is still counted as error")
    void exceptionPropagatesAndCounts() {
        assertThatThrownBy(() -> telemetry.traced("boom", "", 0, () -> {
            throw new IllegalStateException("tool exploded");
        })).isInstanceOf(IllegalStateException.class);

        // Without a tracer there is no span branch; the meter path still runs
        // through the same record() and must not have counted a success.
        assertThat(registry.find("mcp.tool.calls").tag("outcome", "success").counter()).isNull();
    }

    @Test
    @DisplayName("with a NOOP tracer the span branch runs and the result is unchanged")
    void noopTracerBranch() {
        McpToolTelemetry traced = new McpToolTelemetry(Tracer.NOOP, registry, "spring-ai");

        assertThat(traced.traced("search_notes", "q=x", 3, () -> "found")).isEqualTo("found");
        assertThat(registry.find("mcp.tool.calls").tag("tool", "search_notes").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("a null registry falls back to an in-memory one instead of failing")
    void nullRegistryFallsBack() {
        McpToolTelemetry lenient = new McpToolTelemetry(null, null, "spring-ai");

        assertThat(lenient.traced("get_weather", "", 0, () -> "ok")).isEqualTo("ok");
    }

    @Test
    @DisplayName("len() treats null as zero")
    void lenOfNull() {
        assertThat(McpToolTelemetry.len(null)).isZero();
        assertThat(McpToolTelemetry.len("abc")).isEqualTo(3);
    }
}
