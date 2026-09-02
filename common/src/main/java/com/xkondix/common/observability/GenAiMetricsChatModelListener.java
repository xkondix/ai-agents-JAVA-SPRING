package com.xkondix.common.observability;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * CONTENT (gen_ai.prompt / gen_ai.completion) — off by default, switched on
 * through GenAiContentProperties. This is the third gap, and the least
 * obvious: Spring AI can attach the conversation to its span, LangChain4j
 * cannot. Once the raw HTTP client logs were routed to the console only, the
 * LC4j span became the sole place that content could live — and it held none,
 * so the two frameworks were no longer comparable.
 *
 * Tracer is OPTIONAL (nullable): with tracing disabled the listener
 * degrades to metrics-only instead of blowing up. The same rule applies to
 * everything below — a listener must never be the reason a model call fails,
 * so rendering is defensive throughout.
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
    private static final String TRUNCATION_MARKER = "... [truncated]";

    private final MeterRegistry registry;
    private final Tracer tracer; // nullable — metrics-only mode without tracing
    private final GenAiContentProperties content;

    public GenAiMetricsChatModelListener(MeterRegistry registry, Tracer tracer,
                                         GenAiContentProperties content) {
        this.registry = registry;
        this.tracer = tracer;
        this.content = content;
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

            if (content.includePrompt() && context.chatRequest() != null) {
                span.tag("gen_ai.prompt", truncate(render(context.chatRequest().messages())));
            }

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

            AiMessage answer = context.chatResponse() != null
                    ? context.chatResponse().aiMessage() : null;
            if (content.includeCompletion() && answer != null) {
                span.tag("gen_ai.completion", truncate(textOrToolCalls(answer)));
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

    // ── content rendering ─────────────────────────────────────────────────

    /**
     * Flattens the typed message list into one readable block.
     *
     * LangChain4j hands over a List&lt;ChatMessage&gt; of distinct types rather
     * than a string, so the conversation has to be rendered. The format is
     * deliberately plain "role: text" — this is read by a human in Tempo, not
     * parsed.
     *
     * NEVER THROWS. UserMessage.singleText() blows up on a multimodal message,
     * and a listener that throws would take down the model call it is only
     * supposed to observe. Anything unexpected degrades to the message type.
     */
    private String render(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream().map(this::renderOne).collect(Collectors.joining("\n"));
    }

    private String renderOne(ChatMessage message) {
        try {
            return switch (message) {
                case SystemMessage s -> "system: " + s.text();
                case UserMessage u -> "user: " + u.singleText();
                case AiMessage a -> "assistant: " + textOrToolCalls(a);
                case ToolExecutionResultMessage t -> "tool[" + t.toolName() + "]: " + t.text();
                default -> message.type() + ": [unrendered]";
            };
        } catch (RuntimeException e) {
            // e.g. multimodal UserMessage — record the shape, not the failure
            return message.type() + ": [unrenderable: " + e.getClass().getSimpleName() + "]";
        }
    }

    /**
     * The model's answer, or the tool calls it asked for.
     *
     * THIS IS THE PART THAT IS EASY TO GET WRONG. On a tool-calling round the
     * response has finish_reason=tool_calls, content is NULL and the substance
     * sits in tool_calls. A naive aiMessage.text() would therefore tag an empty
     * completion on exactly the most interesting span of the trace.
     */
    private String textOrToolCalls(AiMessage message) {
        if (message.text() != null && !message.text().isBlank()) {
            return message.text();
        }
        if (!message.hasToolExecutionRequests()) {
            return "";
        }
        return message.toolExecutionRequests().stream()
                .map(r -> r.name() + "(" + r.arguments() + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * A span is not a log store. The prompt grows with every agent iteration —
     * by the third round it carries the whole history plus every tool schema —
     * and it all lands in ONE attribute. Past a few kB Tempo starts dropping
     * oversized attributes, silently.
     */
    private String truncate(String value) {
        if (value == null) {
            return "";
        }
        int max = content.maxContentLength();
        if (max <= 0 || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + TRUNCATION_MARKER;
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
