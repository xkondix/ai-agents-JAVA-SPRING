package com.xkondix.multimodal.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Video generation on OpenRouter — a THIRD shape of API in the same module.
 *
 * ── SUBMIT, POLL, DOWNLOAD ─────────────────────────────────────────────────
 *
 * Video is asynchronous because it takes minutes, so it gets its own endpoint
 * family — /api/v1/videos — that looks nothing like the other two:
 *
 *   POST /videos            → 202 with { id, polling_url, status: "pending" }
 *   GET  /videos/{id}       → status, and on completion `unsigned_urls`
 *   GET  /videos/{id}/content?index=0 → the mp4 bytes
 *
 * So this module now talks to one provider through three different protocols:
 * a synchronous JSON call for images, a streaming SSE call for music, and a
 * job queue for video. Same vendor, same key, same base URL. "Unified API" is
 * true at the level of billing and routing, not at the level of code — every
 * modality still needs its own client, and the abstraction that was supposed
 * to hide that (ChatModel) covers exactly one of the three.
 *
 * ── THE URLS ARE NOT PRESIGNED ─────────────────────────────────────────────
 *
 * `unsigned_urls` is named honestly and easy to misread: those links need the
 * SAME Authorization header as the rest. Fetching one without the key gets a
 * 401 that looks like an expired link, and the natural next move — regenerate
 * the video — costs money and fixes nothing.
 *
 * ── COST IS PER SECOND OF OUTPUT ───────────────────────────────────────────
 *
 * Veo 3.1 runs $0.50 per generated second at 720p and $0.75 at 1080p, so an
 * 8-second clip is $4-6. That is fifty to a hundred Lyria songs, and about
 * ten thousand chat calls. The poll response carries `usage.cost`, which is
 * the real figure rather than an estimate — it is logged and fed into
 * mm.generation.* because nothing else in the stack will ever see it.
 *
 * Given that price, the retry budget in MediaCollector matters more here than
 * anywhere else: a tool that returns ERROR and gets retried five times is a
 * twenty-dollar mistake nobody typed.
 */
@Slf4j
@Component
public class VideoClient {

    /** How long to wait for a job before giving up. Veo takes 1-3 minutes. */
    private static final Duration MAX_WAIT = Duration.ofMinutes(8);
    /** The docs recommend 30 s; 10 s keeps a demo responsive without hammering. */
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final String apiKey;

    /** The finished clip plus what OpenRouter actually charged for it. */
    public record Result(byte[] video, double cost) {}

    public VideoClient(ObjectMapper mapper,
                       @Value("${spring.ai.openai.base-url:https://openrouter.ai/api/v1}") String baseUrl,
                       @Value("${spring.ai.openai.api-key}") String apiKey) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.apiKey = apiKey;
    }

    public Result generate(String model, String prompt, int durationSeconds,
                           String resolution, String aspectRatio) {

        String jobId = submit(model, prompt, durationSeconds, resolution, aspectRatio);
        log.info("[VIDEO] job {} submitted, polling…", jobId);

        JsonNode completed = poll(jobId);
        double cost = completed.path("usage").path("cost").asDouble(0.0);

        String contentUrl = completed.path("unsigned_urls").path(0).asString();
        if (contentUrl == null || contentUrl.isBlank()) {
            contentUrl = baseUrl + "/videos/" + jobId + "/content?index=0";
        }

        byte[] bytes = download(contentUrl);
        log.info("[VIDEO] job {} done: {} bytes, cost ${}", jobId, bytes.length, cost);
        return new Result(bytes, cost);
    }

    private String submit(String model, String prompt, int duration,
                          String resolution, String aspectRatio) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        // Unsupported values return a 400 that LISTS what is allowed — the
        // most useful error message in this whole integration. Durations are
        // per model (Veo takes 4/6/8, Wan takes 5/10), which is why the tool
        // description tells the model to stick to small numbers.
        body.put("duration", duration);
        body.put("resolution", resolution);
        body.put("aspect_ratio", aspectRatio);

        JsonNode response = send(HttpRequest.newBuilder(URI.create(baseUrl + "/videos"))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build());

        String id = response.path("id").asString();
        if (id == null || id.isBlank()) {
            throw new IllegalStateException("Video job was not accepted: " + response);
        }
        return id;
    }

    private JsonNode poll(String jobId) {
        Instant deadline = Instant.now().plus(MAX_WAIT);

        while (Instant.now().isBefore(deadline)) {
            sleep(POLL_INTERVAL);

            JsonNode status = send(HttpRequest.newBuilder(
                            URI.create(baseUrl + "/videos/" + jobId))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build());

            String state = status.path("status").asString("");
            log.debug("[VIDEO] job {} → {}", jobId, state);

            switch (state) {
                case "completed" -> {
                    return status;
                }
                // failed / cancelled / expired all carry an `error` field, and
                // all three are terminal — polling on would just burn time.
                case "failed", "cancelled", "expired" -> throw new IllegalStateException(
                        "Video generation " + state + ": "
                                + status.path("error").asString("no reason given"));
                default -> { /* pending or in_progress — keep waiting */ }
            }
        }
        throw new IllegalStateException(
                "Video job " + jobId + " did not finish within " + MAX_WAIT.toMinutes() + " minutes");
    }

    /**
     * The download needs the API key too — see the class comment. Returned as
     * bytes rather than streamed to disk here so MediaStorage stays the only
     * place that writes files.
     */
    private byte[] download(String url) {
        try {
            HttpResponse<byte[]> response = http.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Authorization", "Bearer " + apiKey)
                            .timeout(Duration.ofMinutes(3))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "Downloading the video returned HTTP " + response.statusCode()
                                + " — the content URL needs the Authorization header");
            }
            if (response.body().length == 0) {
                throw new IllegalStateException("The video download was empty");
            }
            return response.body();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while downloading the video", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Video download failed: " + e.getMessage(), e);
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response =
                    http.send(request, HttpResponse.BodyHandlers.ofString());

            JsonNode body = mapper.readTree(response.body());

            // 202 on submit, 200 on poll — anything else carries a reason in
            // the body that is far more useful than the status code alone.
            if (response.statusCode() >= 400) {
                String message = body.path("error").path("message").asString(response.body());
                throw new IllegalStateException(
                        "OpenRouter returned HTTP " + response.statusCode() + ": " + message);
            }
            return body;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during a video API call", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Video API call failed: " + e.getMessage(), e);
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for the video job", e);
        }
    }
}
