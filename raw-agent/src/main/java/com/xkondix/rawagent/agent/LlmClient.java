package com.xkondix.rawagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xkondix.rawagent.config.RawAgentProperties;
import com.xkondix.rawagent.model.ChatRequest;
import com.xkondix.rawagent.model.ChatResponse;
import com.xkondix.rawagent.model.Message;
import com.xkondix.rawagent.model.ToolDefinition;
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
        try {
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

            return objectMapper.readValue(response.body(), ChatResponse.class);

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("LLM call failed: " + e.getMessage(), e);
        }
    }
}
