package com.xkondix.common.observability;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

import java.time.Duration;
import java.util.Map;

/**
 * GenAI metrics AND spans for LangChain4j — the missing counterpart of what
 * Spring AI emits automatically.
 *
 * LangChain4j has no Micrometer integration out of the box: LC4j modules
 * were metrically silent and their traces contained only the bare HTTP
 * server span (no "chat <model>" child). This listener closes both gaps
 * for every LC4j module at once.
 *
 * METRICS — names and tags mirror Spring AI so all agents share panels:
 *   gen.ai.client.token.usage   (counter, gen_ai.token.type=input/output/total)
 *   gen.ai.client.operation     (timer — no ".duration" suffix on purpose)
 *   gen.ai.client.errors, gen.ai.client.tool.requests (extras)
 *
 * SPANS — onRequest() runs synchronously on the calling thread, so the
 * span opened here parents to whatever is in scope (the HTTP server span)
 * and stays in scope for the duration of the model call — log lines from
 * the call get the traceId in MDC for free. The span and its scope travel
 * to onResponse()/onError() through the per-call attributes map.
 * Close order matters: scope first, span second.
 *
 * Tracer is OPTIONAL (nullable): with tracing disabled the listener
 * degrades to metrics-only instead of blowing up.
 *
 * Wiring: LangChain4j Spring Boot starters attach every ChatModelListener
 * bean to the auto-configured models. Registered via
 * AutoConfiguration.imports (see Lc4jGenAiMetricsAutoConfiguration).
 */
public class GenAiMetricsChatModelListener implements ChatModelListener {

    private static final String START_NANOS = "xkondix.metrics.startNanos";
    private static final String SPAN = "xkondix.tracing.span";
    private static final String SCOPE = "xkondix.tracing.scope";
    private static final String UNKNOWN_MODEL = "unknown";

    private final MeterRegistry registry;
    private final Tracer tracer; // nullable — metrics-only mode without tracing

    public GenAiMetricsChatModelListener(MeterRegistry registry, Tracer tracer) {
        this.registry = registry;
        this.tracer = tracer;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_NANOS, System.nanoTime());

        if (tracer != null) {
            String model = requestedModel(context.chatRequest() != null
                    ? context.chatRequest().parameters() : null);
            Span span = tracer.nextSpan().name("chat " + model);
            span.tag("gen_ai.operation.name", "chat");
            span.tag("gen_ai.request.model", model);
            span.tag("framework", "langchain4j");
            Tracer.SpanInScope scope = tracer.withSpan(span.start());
            context.attributes().put(SPAN, span);
            context.attributes().put(SCOPE, scope);
        }
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        String model = modelName(context);

        recordDuration(context.attributes(), model, "none");
        recordTokens(context.chatResponse().tokenUsage(), model);
        recordToolRequests(context, model);

        finishSpan(context.attributes(), span -> {
            TokenUsage usage = context.chatResponse() != null
                    ? context.chatResponse().tokenUsage() : null;
            if (usage != null) {
                if (usage.inputTokenCount() != null) {
                    span.tag("gen_ai.usage.input_tokens", String.valueOf(usage.inputTokenCount()));
                }
                if (usage.outputTokenCount() != null) {
                    span.tag("gen_ai.usage.output_tokens", String.valueOf(usage.outputTokenCount()));
                }
            }
        });
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        String model = requestedModel(context.chatRequest() != null
                ? context.chatRequest().parameters() : null);

        recordDuration(context.attributes(), model,
                context.error() != null ? context.error().getClass().getSimpleName() : "unknown");

        Counter.builder("gen.ai.client.errors")
                .description("LLM calls that ended with an exception")
                .tag("gen_ai.request.model", model)
                .tag("framework", "langchain4j")
                .register(registry)
                .increment();

        finishSpan(context.attributes(), span -> {
            if (context.error() != null) {
                span.error(context.error());
            }
        });
    }

    // ── tracing helpers ───────────────────────────────────────────────────

    private void finishSpan(Map<Object, Object> attributes,
                            java.util.function.Consumer<Span> enricher) {
        Object spanObj = attributes.remove(SPAN);
        Object scopeObj = attributes.remove(SCOPE);
        if (spanObj instanceof Span span) {
            try {
                enricher.accept(span);
            } finally {
                if (scopeObj instanceof Tracer.SpanInScope scope) {
                    scope.close(); // scope first...
                }
                span.end();        // ...span second
            }
        }
    }

    // ── metrics helpers ───────────────────────────────────────────────────

    private void recordDuration(Map<Object, Object> attributes, String model, String error) {
        Object start = attributes.get(START_NANOS);
        if (start instanceof Long startNanos) {
            Timer.builder("gen.ai.client.operation")
                    .description("Duration of a single LLM call (LangChain4j)")
                    .tag("gen_ai.operation.name", "chat")
                    .tag("gen_ai.request.model", model)
                    .tag("framework", "langchain4j")
                    .tag("error", error)
                    .register(registry)
                    .record(Duration.ofNanos(System.nanoTime() - startNanos));
        }
    }

    private void recordTokens(TokenUsage usage, String model) {
        if (usage == null) {
            return;
        }
        incrementTokens(model, "input", usage.inputTokenCount());
        incrementTokens(model, "output", usage.outputTokenCount());
        incrementTokens(model, "total", usage.totalTokenCount());
    }

    private void incrementTokens(String model, String type, Integer count) {
        if (count == null || count <= 0) {
            return;
        }
        Counter.builder("gen.ai.client.token.usage")
                .description("Token usage per LLM call (LangChain4j)")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.token.type", type)
                .tag("framework", "langchain4j")
                .register(registry)
                .increment(count);
    }

    private void recordToolRequests(ChatModelResponseContext context, String model) {
        var aiMessage = context.chatResponse() != null ? context.chatResponse().aiMessage() : null;
        if (aiMessage == null || !aiMessage.hasToolExecutionRequests()) {
            return;
        }
        for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
            Counter.builder("gen.ai.client.tool.requests")
                    .description("Tool calls requested by the model (LangChain4j)")
                    .tag("gen_ai.request.model", model)
                    .tag("gen_ai.tool.name", request.name() != null ? request.name() : "unknown")
                    .tag("framework", "langchain4j")
                    .register(registry)
                    .increment();
        }
    }

    private String modelName(ChatModelResponseContext context) {
        var metadata = context.chatResponse() != null ? context.chatResponse().metadata() : null;
        if (metadata != null && metadata.modelName() != null) {
            return metadata.modelName();
        }
        return requestedModel(context.chatRequest() != null
                ? context.chatRequest().parameters() : null);
    }

    private String requestedModel(dev.langchain4j.model.chat.request.ChatRequestParameters parameters) {
        return parameters != null && parameters.modelName() != null
                ? parameters.modelName() : UNKNOWN_MODEL;
    }
}
