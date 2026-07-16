package com.xkondix.lc4j.mcp.config;

import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;

/**
 * Decorator that adds "tool_call <name>" spans to every tool served by the
 * wrapped ToolProvider (here: McpToolProvider).
 *
 * Why a decorator and not the ChatModelListener: the listener only sees the
 * MODEL call — tool execution happens later, inside AiServices, invoking a
 * ToolExecutor. That executor is the natural seam: it is a plain synchronous
 * call on the calling thread, so
 *   - tracer.nextSpan() parents automatically to the current HTTP span
 *     (same structure as Spring AI and raw-agent traces),
 *   - span.end() sits in a classic try/finally — no cross-callback state,
 *     unlike the listener where the span must travel between onRequest
 *     and onResponse/onError.
 *
 * With a null Tracer the decorator is a transparent pass-through.
 */
@RequiredArgsConstructor
public class TracingToolProvider implements ToolProvider {

    private final ToolProvider delegate;
    private final Tracer tracer; // nullable — pass-through mode

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ToolProviderResult result = delegate.provideTools(request);
        if (tracer == null || result == null) {
            return result;
        }

        ToolProviderResult.Builder traced = ToolProviderResult.builder();
        result.tools().forEach((specification, executor) ->
                traced.add(specification, wrap(specification.name(), executor)));
        return traced.build();
    }

    private ToolExecutor wrap(String toolName, ToolExecutor executor) {
        return (toolRequest, memoryId) -> {
            Span span = tracer.nextSpan().name("tool_call " + toolName);
            span.tag("gen_ai.tool.name", toolName);
            span.tag("framework", "langchain4j");

            try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
                return executor.execute(toolRequest, memoryId);
            } catch (RuntimeException e) {
                span.error(e);
                throw e;
            } finally {
                span.end(); // classic synchronous pattern — finally owns the end
            }
        };
    }
}
