package com.xkondix.multimodal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A hand-written SSE client for Lyria, because the abstraction cannot reach it.
 *
 * ── WHY THIS CLASS EXISTS AND MusicService NO LONGER USES ChatModel ────────
 *
 * Three facts that are each reasonable and together leave no path:
 *
 *   1. OpenRouter serves Lyria on /chat/completions, not /audio/speech.
 *   2. OpenRouter returns audio from that endpoint ONLY with stream: true —
 *      "400: Audio output requires stream: true".
 *   3. Spring AI's OpenAiChatModel maps audio output into Media ONLY in the
 *      NON-streaming path. OpenAiChatModel:385 reads message.audio() off a
 *      completed ChatCompletionMessage; the streaming path builds generations
 *      from `delta`, which carries no audio mapping at all.
 *
 * So the framework supports audio output, the provider supports audio output,
 * and the intersection of the two is empty. The streamed call succeeds, the
 * lyrics arrive, and the audio deltas are dropped on the floor between the SDK
 * and the Media list — no error, because nothing went wrong from any single
 * component's point of view.
 *
 * THAT is the strongest version of this project's whole argument. Everywhere
 * else a layer was silent about a mistake. Here no layer made a mistake: four
 * correct designs compose into something that cannot work, and the only way to
 * find out is to try it and watch a valid response arrive without the bytes.
 *
 * So music drops to raw HTTP. Everything else in the module — chat, vision,
 * images, speech, transcription — still goes through Spring AI. Dropping one
 * modality to the transport is a smaller price than pretending the abstraction
 * covers five when it covers four.
 *
 * ── WHAT THIS DOES NOT GET ─────────────────────────────────────────────────
 *
 * No ChatClient means no ChatModel observations: this call produces no
 * `gen_ai` span and no token metrics. It is covered instead by
 * mm.generation.* in MusicService, which is the honest accounting anyway —
 * Lyria bills per track, not per token. Worth pointing at on the cost slide:
 * the modality that costs the most is the one the standard instrumentation
 * never sees.
 */
@Slf4j
@Component
public class LyriaClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;

    /** Raw result: the mp3 bytes and the timed lyrics Lyria sang. */
    public record Result(byte[] audio, String lyrics) {}

    public LyriaClient(ObjectMapper mapper,
                       @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl,
                       @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public Result generate(String model, String prompt) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        // Both are required, and for different reasons: modalities tells the
        // provider to compose audio at all, stream is what makes it send the
        // audio back. Dropping either one yields a valid, audio-free answer.
        body.put("modalities", List.of("text", "audio"));
        body.put("audio", Map.of("format", "mp3"));
        body.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                // Composing a full song can take minutes; the default timeout
                // would cut it off mid-track and look like a provider fault.
                .timeout(Duration.ofMinutes(5))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();

        try {
            HttpResponse<Stream<String>> response =
                    http.send(request, HttpResponse.BodyHandlers.ofLines());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Lyria returned HTTP " + response.statusCode());
            }
            return consume(response.body());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while generating music", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Lyria request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Server-sent events: lines prefixed with "data: ", terminated by
     * "data: [DONE]". Audio arrives as base64 deltas that must be
     * concatenated IN ORDER — this is a single mp3 sliced up, not a set of
     * independent files.
     */
    private Result consume(Stream<String> lines) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        StringBuilder lyrics = new StringBuilder();
        int[] blocks = {0};

        lines.forEach(line -> {
            if (!line.startsWith("data:")) {
                return;
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) {
                return;
            }
            try {
                JsonNode root = mapper.readTree(payload);

                // OpenRouter reports upstream refusals (Gemini's content
                // filter, for one) inside a 200 stream rather than as an HTTP
                // error. Surfacing it here turns a mystery empty result into
                // a sentence the user can act on.
                JsonNode error = root.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new IllegalStateException(
                            "Lyria refused: " + error.path("message").asString());
                }

                JsonNode delta = root.path("choices").path(0).path("delta");

                JsonNode data = delta.path("audio").path("data");
                if (data.isString() && !data.asString().isEmpty()) {
                    audio.writeBytes(Base64.getDecoder().decode(data.asString()));
                    blocks[0]++;
                }

                JsonNode transcript = delta.path("audio").path("transcript");
                if (transcript.isString()) {
                    lyrics.append(transcript.asString());
                }
                JsonNode content = delta.path("content");
                if (content.isString()) {
                    lyrics.append(content.asString());
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (RuntimeException e) {
                // One malformed frame must not discard a track that is
                // otherwise arriving correctly.
                log.debug("[MUSIC] skipping unparsable SSE frame: {}", e.getMessage());
            }
        });

        if (audio.size() == 0) {
            throw new IllegalStateException(
                    "Lyria streamed a response but sent no audio deltas");
        }
        log.info("[MUSIC] reassembled {} audio deltas into {} bytes", blocks[0], audio.size());
        return new Result(audio.toByteArray(), lyrics.toString());
    }
}
