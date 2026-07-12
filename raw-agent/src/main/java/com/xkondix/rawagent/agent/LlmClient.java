package com.xkondix.rawagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xkondix.rawagent.config.RawAgentProperties;
import com.xkondix.rawagent.model.ChatRequest;
import com.xkondix.rawagent.model.ChatResponse;
import com.xkondix.rawagent.model.Message;
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
 * OBSERVABILITY BY HAND — the third rung of the ladder:
 *   raw-agent    → you create spans and count tokens yourself (this class),
 *   LangChain4j  → ChatModelListener (GenAiMetricsChatModelListener in common),
 *   Spring AI    → automatic (ChatClient observation conventions).
 * Span name ("chat <model>"), gen_ai.* attributes and metric names
 * deliberately mirror Spring AI:
 *   gen.ai.client.token.usage   (counter)
 *   gen.ai.client.operation     (timer — NOTE: no ".duration" suffix; Spring AI
 *                                exports gen_ai_client_operation_milliseconds
 *                                and a mismatched name lands on nobody's panel)
 * framework=raw separates the series on shared panels.
 *
 * HttpClient is a singleton — created once at startup (@PostConstruct)
 * and reused for all requests. Creating a new client per request is
 * wasteful and defeats connection pooling.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LlmClient {

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
        span.tag("framework", "raw");

        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
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

    private void recordDuration(String model, String error, long startNanos) {
        Timer.builder("gen.ai.client.operation")
                .description("Duration of a single LLM call (raw java.net.http)")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", model)
                .tag("framework", "raw")
                .tag("error", error)
                .register(meterRegistry)
                .record(Duration.ofNanos(System.nanoTime() - startNanos));
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
        Counter.builder("gen.ai.client.token.usage")
                .description("Token usage per LLM call (raw java.net.http)")
                .tag("gen_ai.operation.name", "chat")
                .tag("gen_ai.request.model", model)
                .tag("gen_ai.token.type", type)
                .tag("framework", "raw")
                .register(meterRegistry)
                .increment(count);
    }

    private void recordFailure(String model, Exception e, long startNanos) {
        recordDuration(model, e.getClass().getSimpleName(), startNanos);
        Counter.builder("gen.ai.client.errors")
                .description("LLM calls that ended with an exception (raw)")
                .tag("gen_ai.request.model", model)
                .tag("framework", "raw")
                .register(meterRegistry)
                .increment();
    }
}
