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

import java.time.Duration;
import java.util.Map;

/**
 * GenAI metrics for LangChain4j — the missing counterpart of what Spring AI
 * emits automatically.
 *
 * LangChain4j has NO Micrometer integration out of the box, so the LC4j
 * modules were metrically silent while spring-ai-* modules populated the
 * Grafana dashboard. This listener closes the gap.
 *
 * DESIGN DECISION: metric names and tag keys deliberately mirror the ones
 * Spring AI publishes (gen.ai.client.token.usage /
 * gen.ai.client.operation.duration with gen_ai.request.model and
 * gen_ai.token.type tags), so all four agent modules land on the SAME
 * dashboard panels and become directly comparable. The extra tag
 * framework=langchain4j lets you split the series when you want the
 * comparison view.
 *
 * Wiring: the LangChain4j Spring Boot starters attach every
 * ChatModelListener bean from the context to the auto-configured models —
 * no changes to model construction are needed. Registered via
 * AutoConfiguration.imports (see Lc4jGenAiMetricsAutoConfiguration).
 */
public class GenAiMetricsChatModelListener implements ChatModelListener {

    private static final String START_NANOS = "xkondix.metrics.startNanos";
    private static final String UNKNOWN_MODEL = "unknown";

    private final MeterRegistry registry;

    public GenAiMetricsChatModelListener(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        context.attributes().put(START_NANOS, System.nanoTime());
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        String model = modelName(context.attributes(), context);

        recordDuration(context.attributes(), model, "none");
        recordTokens(context.chatResponse().tokenUsage(), model);
        recordToolRequests(context, model);
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
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void recordDuration(Map<Object, Object> attributes, String model, String error) {
        Object start = attributes.get(START_NANOS);
        if (start instanceof Long startNanos) {
            Timer.builder("gen.ai.client.operation.duration")
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

    private String modelName(Map<Object, Object> attributes, ChatModelResponseContext context) {
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
