package com.xkondix.common.observability;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Adds "tool_call &lt;name&gt;" spans to every tool served by a ToolProvider.
 *
 * WHY THIS EXISTS
 * LangChain4j creates no tool spans of its own, and the metrics listener
 * (GenAiMetricsChatModelListener) only sees MODEL calls — tool execution
 * happens later, inside AiServices, through a ToolExecutor. That executor is
 * the natural seam, and it is a plain synchronous call on the calling thread:
 *   - tracer.nextSpan() parents automatically to the current HTTP span
 *     (so traces get the same shape as Spring AI and raw-agent ones),
 *   - span.end() sits in a classic try/finally — no cross-callback state,
 *     unlike the listener where a span must travel between onRequest and
 *     onResponse/onError.
 *
 * TWO WAYS TO USE IT
 *   1. wrap an existing provider (e.g. McpToolProvider):
 *        new TracingToolProvider(mcpToolProvider, tracer)
 *   2. build one from an object with @Tool methods (local tools) and wrap it:
 *        TracingToolProvider.fromAnnotatedObject(milanTools, tracer)
 *      Use this INSTEAD of AiServices.tools(obj) — tools passed that way
 *      bypass the provider entirely and stay invisible in traces.
 *
 * With a null Tracer both variants degrade to a transparent pass-through.
 */
public class TracingToolProvider implements ToolProvider {

    private final ToolProvider delegate;
    private final Tracer tracer; // nullable — pass-through mode

    public TracingToolProvider(ToolProvider delegate, Tracer tracer) {
        this.delegate = delegate;
        this.tracer = tracer;
    }

    /**
     * Builds a ToolProvider from an object whose methods are annotated with
     * {@code @Tool}, then wraps it so every execution produces a span.
     *
     * This mirrors what AiServices.tools(Object) does internally
     * (ToolSpecifications scan + DefaultToolExecutor per method), except the
     * result goes through a provider we can decorate.
     */
    public static ToolProvider fromAnnotatedObject(Object toolsObject, Tracer tracer) {
        ToolProvider plain = request -> {
            ToolProviderResult.Builder builder = ToolProviderResult.builder();
            for (ToolSpecification specification :
                    ToolSpecifications.toolSpecificationsFrom(toolsObject)) {
                Method method = findMethod(toolsObject, specification.name());
                builder.add(specification, new DefaultToolExecutor(toolsObject, method));
            }
            return builder.build();
        };
        return new TracingToolProvider(plain, tracer);
    }

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

    /**
     * Tool name == method name in LangChain4j unless @Tool declares its own
     * name; the specification carries the effective name, so match on it and
     * fall back to the method name.
     */
    private static Method findMethod(Object toolsObject, String toolName) {
        return Arrays.stream(toolsObject.getClass().getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(dev.langchain4j.agent.tool.Tool.class))
                .filter(m -> {
                    var annotation = m.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
                    String declared = annotation.name();
                    return toolName.equals(declared.isEmpty() ? m.getName() : declared);
                })
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No @Tool method named '" + toolName + "' on "
                                + toolsObject.getClass().getName()));
    }
}
