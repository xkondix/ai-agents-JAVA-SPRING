package com.xkondix.rawagent.agent;

import com.xkondix.rawagent.config.RawAgentProperties;
import com.xkondix.rawagent.model.ChatRequest;
import com.xkondix.rawagent.model.ChatResponse;
import com.xkondix.rawagent.model.Message;
import com.xkondix.rawagent.model.ToolCall;
import com.xkondix.rawagent.model.ToolDefinition;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Raw HTTP client for LLM API calls.
 * Uses java.net.http.HttpClient — zero external dependencies.
 *
 * Supports:
 *   - Ollama       (http://localhost:11434/v1/chat/completions)
 *   - OpenAI       (https://api.openai.com/v1/chat/completions)
 *   - OpenRouter   (https://openrouter.ai/api/v1/chat/completions)
 *
 * This is what LangChain4j and Spring AI do internally —
 * they just add abstractions on top.
 *
 * JACKSON 3 (Spring Boot 4): the injected ObjectMapper is
 * tools.jackson.databind.ObjectMapper. Boot 4 auto-configures only that type;
 * Jackson 2 remains on the classpath (managed by the Boot BOM) so old imports
 * still COMPILE, which makes this a startup failure rather than a build one:
 *   "Parameter 1 of constructor in LlmClient required a bean of type
 *    'com.fasterxml.jackson.databind.ObjectMapper' that could not be found."
 * The method names used here (writeValueAsString, readValue) are unchanged.
 *
 * OBSERVABILITY BY HAND — the third rung of the ladder:
 *   raw-agent    → you create spans and count tokens yourself (this class),
 *   LangChain4j  → ChatModelListener (GenAiMetricsChatModelListener in common),
 *   Spring AI    → automatic (ChatClient observation conventions).
 * Span name ("chat <model>"), gen_ai.* attributes and metric names
 * deliberately mirror Spring AI:
 *   gen.ai.client.token.usage    (counter)
 *   gen.ai.client.operation      (timer — NOTE: no ".duration" suffix; Spring AI
 *                                 exports gen_ai_client_operation_milliseconds
 *                                 and a mismatched name lands on nobody's panel)
 *   gen.ai.client.tool.requests  (counter — tool calls the model ASKED for,
 *                                 same meter and meaning as the LC4j listener)
 * framework=raw separates the series on shared panels.
 *
 * ── METERS ARE REGISTERED AT STARTUP, WITH ZERO ─────────────────────────────
 * Micrometer creates a meter on first use. Left alone, the token counter of a
 * fresh process is born on the first LLM call already holding e.g. 151, and
 * that is the first value Prometheus ever sees: a flat line 151, 151, 151…
 * rate() and increase() measure CHANGE between samples, so the first request
 * after a restart is invisible on every rate panel and counts as $0 on the cost
 * panel — observed on 2026-09-02 as "Modules with LLM metrics: 2" out of 7.
 * registerMeters() creates the counters and the success timer in init(), so
 * the very first push carries a 0 and the first increment is a real increase.
 * Tags must match the ones used on increment EXACTLY — a different tag set is
 * a different meter, and the pre-registered one would just stay at 0.
 *
 * HttpClient is a singleton — created once at startup (@PostConstruct)
 * and reused for all requests. Creating a new client per request is
 * wasteful and defeats connection pooling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

    private static final String FRAMEWORK = "raw";

    private final RawAgentProperties props;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final Tracer tracer;

    // Singleton — reused across all requests
    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        log.info("[LLM] HttpClient initialized. Provider: {}", props.getProvider());
        registerMeters(configuredModel());
    }

    public ChatResponse chat(List<Message> messages, List<ToolDefinition> tools) {
        return switch (props.getProvider()) {
            case "ollama" -> callOllama(messages, tools);
            case "openai" -> callOpenAi(messages, tools);
            default -> throw new IllegalStateException(
                    "Unknown provider: " + props.getProvider()
                    + ". Use: ollama or openai");
        };
    }

    // ── Ollama ────────────────────────────────────────────────────────────

    private ChatResponse callOllama(List<Message> messages, List<ToolDefinition> tools) {
        var cfg = props.getOllama();
        // Ollama supports OpenAI-compatible endpoint /v1/chat/completions
        // which handles tool calls exactly like OpenAI
        String url = cfg.getBaseUrl() + "/v1/chat/completions";

        var body = new ChatRequest(
                cfg.getModel(),
                messages,
                tools.isEmpty() ? null : tools,
                cfg.getTemperature(),
                false);

        log.debug("[LLM] Ollama request: model={} messages={} tools={}",
                cfg.getModel(), messages.size(),
                tools.isEmpty() ? 0 : tools.size());

        return doPost(url, null, body, cfg.getTimeoutSeconds());
    }

    // ── OpenAI / OpenRouter ───────────────────────────────────────────────

    private ChatResponse callOpenAi(List<Message> messages, List<ToolDefinition> tools) {
        var cfg = props.getOpenai();
        String url = cfg.getBaseUrl() + "/chat/completions";

        var body = new ChatRequest(
                cfg.getModel(),
                messages,
                tools.isEmpty() ? null : tools,
                cfg.getTemperature(),
                false);

        log.debug("[LLM] OpenAI request: model={} messages={} tools={}",
                cfg.getModel(), messages.size(),
                tools.isEmpty() ? 0 : tools.size());

        return doPost(url, "Bearer " + cfg.getApiKey(), body, cfg.getTimeoutSeconds());
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private ChatResponse doPost(String url, String authHeader,
                                ChatRequest body, int timeoutSeconds) {
        long startNanos = System.nanoTime();

        // Manual "chat <model>" span — Spring AI creates this automatically,
        // here we do it with the low-level Tracer API. nextSpan() parents to
        // the current span (HTTP server span / agent loop context).
        Span span = tracer.nextSpan().name("chat " + body.model());
        span.tag("gen_ai.operation.name", "chat");
        span.tag("gen_ai.request.model", body.model());
        span.tag("gen_ai.system", props.getProvider());
        span.tag("framework", FRAMEWORK);

        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            // Jackson 3 throws the UNCHECKED JacksonException instead of the
            // checked JsonProcessingException. The catch blocks below already
            // cover RuntimeException, so behaviour is unchanged — but a
            // "catch (JsonProcessingException)" elsewhere would now be a
            // compile error for catching an impossible checked exception.
            String json = objectMapper.writeValueAsString(body);
            log.debug("[LLM] POST {}", url);

            HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (authHeader != null) {
                reqBuilder.header("Authorization", authHeader);
            }

            HttpResponse<String> response = httpClient.send(
                    reqBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            log.debug("[LLM] Response status={}", response.statusCode());

            if (response.statusCode() != 200) {
                throw new RuntimeException(
                        "LLM API error " + response.statusCode()
                        + ": " + response.body());
            }

            ChatResponse chatResponse =
                    objectMapper.readValue(response.body(), ChatResponse.class);

            enrichSpan(span, chatResponse);
            recordDuration(body.model(), "none", startNanos);
            recordTokens(body.model(), chatResponse.usage());
            recordToolRequests(body.model(), chatResponse);
            return chatResponse;

        } catch (RuntimeException e) {
            span.error(e);
            recordFailure(body.model(), e, startNanos);
            throw e;
        } catch (Exception e) {
            span.error(e);
            recordFailure(body.model(), e, startNanos);
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        } finally {
            span.end();
        }
    }

    // ── Tracing (by hand) ─────────────────────────────────────────────────

    /** Same gen_ai.* attributes you see on Spring AI spans in Tempo. */
    private void enrichSpan(Span span, ChatResponse response) {
        if (response.usage() != null) {
            span.tag("gen_ai.usage.input_tokens",
                    String.valueOf(response.usage().promptTokens()));
            span.tag("gen_ai.usage.output_tokens",
                    String.valueOf(response.usage().completionTokens()));
            span.tag("gen_ai.usage.total_tokens",
                    String.valueOf(response.usage().totalTokens()));
        }
        if (response.choices() != null && !response.choices().isEmpty()
                && response.choices().get(0).finishReason() != null) {
            span.tag("gen_ai.response.finish_reasons",
                    response.choices().get(0).finishReason());
        }
    }

    // ── Metrics (by hand — no framework does this for us here) ───────────

    /**
     * The model name this process will tag its meters with, taken from the
     * active provider's configuration. It must equal the model sent in the
     * request body, otherwise the pre-registered meters and the real ones are
     * two different series.
     */
    private String configuredModel() {
        return switch (props.getProvider()) {
            case "ollama" -> props.getOllama().getModel();
            case "openai" -> props.getOpenai().getModel();
            default -> "unknown";
        };
    }

    /** Pre-registers the meters at 0 — see the class comment. */
    private void registerMeters(String model) {
        for (String type : List.of("input", "output", "total")) {
            tokenCounter(model, type);
        }
        durationTimer(model, "none");
        log.info("[OBSERVABILITY] raw-agent meters pre-registered at 0 for model={} "
                + "(gen.ai.client.token.usage, gen.ai.client.operation)", model);
    }

    private Counter tokenCounter(String model, String type) {
        return Counter.builder("gen.ai.client.token.usage")
                .description("Token usage per LLM call (raw java.net.http)")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.token.type", type)
                .tag("framework", FRAMEWORK)
                .register(meterRegistry);
    }

    private Timer durationTimer(String model, String error) {
        return Timer.builder("gen.ai.client.operation")
                .description("Duration of a single LLM call (raw java.net.http)")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", model)
                .tag("framework", FRAMEWORK)
                .tag("error", error)
                .register(meterRegistry);
    }

    private void recordDuration(String model, String error, long startNanos) {
        durationTimer(model, error).record(Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private void recordTokens(String model, ChatResponse.Usage usage) {
        if (usage == null) {
            return; // some providers omit usage — never break the call over metrics
        }
        incrementTokens(model, "input", usage.promptTokens());
        incrementTokens(model, "output", usage.completionTokens());
        incrementTokens(model, "total", usage.totalTokens());
    }

    private void incrementTokens(String model, String type, int count) {
        if (count <= 0) {
            return;
        }
        tokenCounter(model, type).increment(count);
    }

    /**
     * Tool calls the model asked for in this response — same meter name and
     * tags as the LangChain4j listener, so both land on the "Tool calls/min —
     * all three frameworks" panel. Spring AI counts EXECUTIONS instead
     * (spring_ai_tool); the difference is one sentence on stage.
     */
    private void recordToolRequests(String model, ChatResponse response) {
        var message = response.firstMessage();
        if (message == null || message.toolCalls() == null) {
            return;
        }
        for (ToolCall call : message.toolCalls()) {
            String tool = call.function() != null && call.function().name() != null
                    ? call.function().name() : "unknown";
            Counter.builder("gen.ai.client.tool.requests")
                    .description("Tool calls requested by the model (raw)")
                    .tag("gen_ai.request.model", model)
                    .tag("gen_ai.tool.name", tool)
                    .tag("framework", FRAMEWORK)
                    .register(meterRegistry)
                    .increment();
        }
    }

    private void recordFailure(String model, Exception e, long startNanos) {
        recordDuration(model, e.getClass().getSimpleName(), startNanos);
        Counter.builder("gen.ai.client.errors")
                .description("LLM calls that ended with an exception (raw)")
                .tag("gen_ai.request.model", model)
                .tag("framework", FRAMEWORK)
                .register(meterRegistry)
                .increment();
    }
}
