package com.xkondix.claude.mcp.server.tools;

import com.xkondix.claude.mcp.server.config.ClaudeMcpProperties;
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
 * Covers the two things that were broken for weeks and produced no error.
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
 * Both are cheap to assert and neither needs a Spring context.
 */
@DisplayName("CodeToolsService tracing")
class CodeToolsServiceTracingTest {

    @TempDir
    Path root;

    private FileService fileService;

    @BeforeEach
    void setUp() throws IOException {
        fileService = new FileService(new ClaudeMcpProperties(
                root.toString(), List.of(".md"), List.of("target")));
        Files.writeString(root.resolve("notes.md"), "content from disk");
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<Tracer> providerOf(Tracer tracer) {
        ObjectProvider<Tracer> provider = mock(ObjectProvider.class);
        given(provider.getIfAvailable()).willReturn(tracer);
        return provider;
    }

    @Test
    @DisplayName("without a Tracer bean the tools still return real content")
    void worksWithoutTracer() {
        CodeToolsService tools = new CodeToolsService(fileService, providerOf(null));

        assertThat(tools.read_file("notes.md"))
                .as("degrading to 'no spans' must not degrade to 'no answer'")
                .isEqualTo("content from disk");
    }

    @Test
    @DisplayName("a successful call is wrapped in a span that is started, tagged and ended")
    void createsSpanAroundSuccessfulCall() {
        Span span = mock(Span.class);
        given(span.name(anyString())).willReturn(span);
        given(span.tag(anyString(), anyString())).willReturn(span);
        given(span.start()).willReturn(span);

        Tracer tracer = mock(Tracer.class);
        given(tracer.nextSpan()).willReturn(span);
        given(tracer.withSpan(any())).willReturn(mock(Tracer.SpanInScope.class));

        CodeToolsService tools = new CodeToolsService(fileService, providerOf(tracer));

        assertThat(tools.read_file("notes.md")).isEqualTo("content from disk");

        verify(span).name("mcp_tool read_file");
        verify(span).tag("gen_ai.tool.name", "read_file");
        verify(span).tag("gen_ai.operation.name", "execute_tool");
        verify(span).tag("mcp.tool.result.length", "17");
        verify(span).end();
    }

    @Test
    @DisplayName("the span is ended even when the operation blows up")
    void endsSpanOnFailure() {
        Span span = mock(Span.class);
        given(span.name(anyString())).willReturn(span);
        given(span.tag(anyString(), anyString())).willReturn(span);
        given(span.start()).willReturn(span);

        Tracer tracer = mock(Tracer.class);
        given(tracer.nextSpan()).willReturn(span);
        given(tracer.withSpan(any())).willReturn(mock(Tracer.SpanInScope.class));
        given(tracer.currentSpan()).willReturn(span);

        CodeToolsService tools = new CodeToolsService(fileService, providerOf(tracer));

        assertThat(tools.read_file("../escape.md"))
                .as("a sandbox violation is reported to the model as text")
                .startsWith("ERROR: Path traversal detected");

        verify(span).tag("error.type", "SecurityException");
        verify(span).end();
    }

    @Test
    @DisplayName("delete_file requires the exact confirmation literal")
    void deleteRequiresConfirmation() {
        CodeToolsService tools = new CodeToolsService(fileService, providerOf(null));

        assertThat(tools.delete_file("notes.md", "yes"))
                .startsWith("ERROR: confirm field must be exactly");

        assertThat(Files.exists(root.resolve("notes.md")))
                .as("a rejected confirmation must leave the file alone")
                .isTrue();
    }
}
