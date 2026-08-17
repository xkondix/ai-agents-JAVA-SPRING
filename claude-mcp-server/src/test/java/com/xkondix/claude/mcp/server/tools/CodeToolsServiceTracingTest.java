package com.xkondix.claude.mcp.server.tools;

import com.xkondix.claude.mcp.server.config.ClaudeMcpProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Covers the things that were broken for weeks and produced no error.
 *
 * 1. The tools returned an empty string. The tracing block had been commented
 *    out during debugging and the operation call went with it, leaving
 *    `return "";` behind. Every tool "succeeded" and answered nothing.
 *
 * 2. The Tracer bean was missing after the Boot 4 upgrade (tracing
 *    auto-configuration moved out of actuator), and because it was injected as
 *    a hard constructor parameter, the whole application context failed to
 *    start. Telemetry taking down a file-editing tool is the wrong trade.
 *
 * 3. Spans were created with kind INTERNAL, so Tempo's metrics generator never
 *    treated this service as a node in the service graph and produced no RED
 *    metrics for it. Nothing about that is visible in a trace view unless you
 *    know to look at "Kind".
 *
 * 4. Payload size was recorded for the response only, which made write_file —
 *    the call that ships an entire file body as an argument — look free.
 *
 * None of this needs a Spring context.
 */
@DisplayName("CodeToolsService telemetry")
class CodeToolsServiceTracingTest {

    @TempDir
    Path root;

    private FileService fileService;
    private MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(new ClaudeMcpProperties(
                root.toString(), List.of(".md"), List.of("target")));
        meterRegistry = new SimpleMeterRegistry();
        Files.writeString(root.resolve("notes.md"), "content from disk");
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(value);
        given(provider.getIfAvailable(any())).willReturn(value);
        return provider;
    }

    /** A registry is always present, so it is passed as a real object, never null. */
    private CodeToolsService toolsWith(Tracer tracer) {
        ObjectProvider<MeterRegistry> registries = providerOf(meterRegistry);
        return new CodeToolsService(fileService, providerOf(tracer), registries);
    }

    /** Builder mock wired so every fluent call returns itself and start() yields the span. */
    private static Tracer tracerReturning(Span span) {
        Span.Builder builder = mock(Span.Builder.class);
        given(builder.name(anyString())).willReturn(builder);
        given(builder.kind(any())).willReturn(builder);
        given(builder.tag(anyString(), anyString())).willReturn(builder);
        given(builder.start()).willReturn(span);

        Tracer tracer = mock(Tracer.class);
        given(tracer.spanBuilder()).willReturn(builder);
        given(tracer.withSpan(any())).willReturn(mock(Tracer.SpanInScope.class));
        return tracer;
    }

    private static Span mockSpan() {
        Span span = mock(Span.class);
        given(span.tag(anyString(), anyString())).willReturn(span);
        return span;
    }

    @Test
    @DisplayName("without a Tracer bean the tools still return real content")
    void worksWithoutTracer() {
        CodeToolsService tools = toolsWith(null);

        assertThat(tools.read_file("notes.md"))
                .as("degrading to 'no spans' must not degrade to 'no answer'")
                .isEqualTo("content from disk");
    }

    @Test
    @DisplayName("a successful call is wrapped in a SERVER span that is tagged and ended")
    void createsServerSpanAroundSuccessfulCall() {
        Span span = mockSpan();
        Tracer tracer = tracerReturning(span);

        assertThat(toolsWith(tracer).read_file("notes.md")).isEqualTo("content from disk");

        Span.Builder builder = tracer.spanBuilder();
        verify(builder).name("mcp_tool read_file");
        verify(builder).kind(Span.Kind.SERVER);
        verify(builder).tag("gen_ai.tool.name", "read_file");
        verify(builder).tag("gen_ai.operation.name", "execute_tool");
        verify(span).tag("mcp.tool.response.length", "17");
        verify(span).end();
    }

    @Test
    @DisplayName("span kind SERVER is what makes Tempo generate RED metrics for this service")
    void spanKindIsServerNotInternal() {
        Tracer tracer = tracerReturning(mockSpan());

        toolsWith(tracer).read_file("notes.md");

        verify(tracer.spanBuilder()).kind(Span.Kind.SERVER);
    }

    @Test
    @DisplayName("the span is ended even when the operation blows up")
    void endsSpanOnFailure() {
        Span span = mockSpan();
        Tracer tracer = tracerReturning(span);
        given(tracer.currentSpan()).willReturn(span);

        assertThat(toolsWith(tracer).read_file("../escape.md"))
                .as("a sandbox violation is reported to the model as text")
                .startsWith("ERROR: Path traversal detected");

        verify(span).tag("error.type", "SecurityException");
        verify(span).end();
    }

    @Test
    @DisplayName("a successful call is counted with outcome=success and timed")
    void recordsMetricsForSuccess() {
        toolsWith(null).read_file("notes.md");

        assertThat(meterRegistry.get("mcp.tool.calls")
                .tags("tool", "read_file", "outcome", "success")
                .counter().count()).isEqualTo(1.0);

        assertThat(meterRegistry.get("mcp.tool.duration")
                .tags("tool", "read_file", "outcome", "success")
                .timer().count()).isEqualTo(1L);
    }

    @Test
    @DisplayName("a failure is counted as outcome=error even though nothing is thrown")
    void recordsMetricsForFailure() {
        toolsWith(null).read_file("../escape.md");

        assertThat(meterRegistry.get("mcp.tool.calls")
                .tags("tool", "read_file", "outcome", "error")
                .counter().count())
                .as("errors are returned as text, so counting thrown exceptions "
                        + "would report a permanent 0% error rate")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("read_file sends a short path and returns the file: response dwarfs request")
    void recordsPayloadSizeInBothDirections() {
        toolsWith(null).read_file("notes.md");

        assertThat(payload("read_file", "request"))
                .as("the argument is just the path")
                .isEqualTo(8.0);
        assertThat(payload("read_file", "response"))
                .as("the response is the whole file")
                .isEqualTo(17.0);
    }

    @Test
    @DisplayName("write_file inverts the ratio — measuring only the response would hide its cost")
    void writeFilePayloadIsMostlyRequest() {
        String content = "x".repeat(500);

        assertThat(toolsWith(null).write_file("notes.md", content))
                .startsWith("OK: File written");

        double request = payload("write_file", "request");
        double response = payload("write_file", "response");

        assertThat(request)
                .as("path + full file body travels IN")
                .isEqualTo(508.0);
        assertThat(response)
                .as("only a short confirmation travels OUT")
                .isLessThan(request);
    }

    private double payload(String tool, String direction) {
        return meterRegistry.get("mcp.tool.payload.size")
                .tags("tool", tool, "direction", direction)
                .summary().totalAmount();
    }

    @Test
    @DisplayName("delete_file requires the exact confirmation literal")
    void deleteRequiresConfirmation() {
        CodeToolsService tools = toolsWith(null);

        assertThat(tools.delete_file("notes.md", "yes"))
                .startsWith("ERROR: confirm field must be exactly");

        assertThat(Files.exists(root.resolve("notes.md")))
                .as("a rejected confirmation must leave the file alone")
                .isTrue();
    }
}
