package com.xkondix.multimodal.tools;

import com.xkondix.multimodal.media.MediaCollector;
import com.xkondix.multimodal.media.MediaStorage;
import com.xkondix.multimodal.service.VisionAgent;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.tts.TextToSpeechModel;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.Base64;

/**
 * Every modality is a tool, so the MODEL does the routing.
 *
 * This is the routing pattern from Patterns Lab one level up: there is no
 * classifier and no switch. The router agent is told what it can reach for
 * and picks — and because Spring AI 2.0 runs tool execution inside
 * ToolCallingAdvisor, it keeps picking until it is done. "Here is a photo,
 * draw a modern version and read the caption out loud" resolves to
 * analyze_image → generate_image → speak_text with no loop in our code.
 *
 * Worth saying next to patterns-spring-ai, where the loop is written by hand:
 * the same iteration, but here the model owns the plan, so the iteration
 * belongs to the framework rather than to us.
 *
 * TOOLS RETURN A SENTENCE WITH A URL, NEVER THE BYTES. A tool result goes
 * back into the conversation, so returning base64 would push a megabyte into
 * the next prompt — paid per token, repeated on every following turn, and
 * copied into the span attributes and Loki where it silently exceeds the
 * limits. The file goes to disk; the model gets a path.
 *
 * AND THE URL IS ALSO REPORTED TO MediaCollector, because the model cannot be
 * trusted to echo it. On the first real run gpt-4o-mini turned
 * /api/v1/multimodal/media/…png into
 * ![Cat](https://api.v1/multimodal/media/…png) — invented host, no leading
 * slash, wrapped in markdown. The artifact existed; the UI showed nothing.
 * Instructions are not contracts: what the interface depends on must be
 * produced by code.
 *
 * ── TWO API NOTES FOR SPRING AI 2.0 ────────────────────────────────────────
 *
 * 1. TTS WAS RENAMED. org.springframework.ai.openai.audio.speech.* moved to
 *    org.springframework.ai.audio.tts.*, SpeechModel became
 *    TextToSpeechModel, and `speed` went from Float to Double. Every tutorial
 *    online still shows the old names — the rename fails loudly, the
 *    Float→Double would have been the quiet one.
 *
 * 2. THE IMAGE RESULT IS EITHER A URL OR BASE64, DEPENDING ON THE MODEL.
 *    Older OpenAI models answer with a temporary URL; gpt-image-* answers
 *    with b64Json and no URL at all. Reading only one of the two is how you
 *    get a NullPointerException three weeks after it worked. Both are handled
 *    here, and a remote URL is downloaded immediately so the artifact
 *    outlives the provider's expiry window.
 */
@Slf4j
@Service
public class MultimodalTools {

    private final ImageModel imageModel;
    private final TextToSpeechModel speechModel;
    private final VisionAgent visionAgent;
    private final MediaStorage storage;
    private final MediaCollector collector;
    private final MeterRegistry registry;

    public MultimodalTools(ImageModel imageModel,
                           TextToSpeechModel speechModel,
                           VisionAgent visionAgent,
                           MediaStorage storage,
                           MediaCollector collector,
                           MeterRegistry registry) {
        this.imageModel = imageModel;
        this.speechModel = speechModel;
        this.visionAgent = visionAgent;
        this.storage = storage;
        this.collector = collector;
        this.registry = registry;
    }

    @Tool(description = """
            Look at a picture the user attached and describe it, or answer a
            question about it. You cannot see images yourself — this is the
            only way to find out what is in one. Pass the image URL exactly as
            it was given to you.
            """)
    public String analyze_image(
            @ToolParam(description = "The image URL from the conversation, e.g. /api/v1/multimodal/media/2026-09-05/abc.png")
            String image_url,
            @ToolParam(description = "What you want to know about the picture", required = false)
            String question) {

        try {
            return visionAgent.analyze(image_url, question);
        } catch (RuntimeException e) {
            return "ERROR: could not analyze the image — " + e.getMessage();
        }
    }

    @Tool(description = """
            Generate a picture from a text description and save it.
            Use this whenever the user asks to draw, illustrate, visualise or
            picture something. The picture is shown to the user automatically —
            just say what you drew.
            """)
    public String generate_image(
            @ToolParam(description = "Detailed English description of the picture to draw")
            String description) {

        log.info("[MM] generate_image: {}", abbreviate(description));
        Timer.Sample sample = Timer.start(registry);
        try {
            ImageResponse response = imageModel.call(new ImagePrompt(description));
            var image = response.getResult().getOutput();

            byte[] bytes;
            if (image.getB64Json() != null && !image.getB64Json().isBlank()) {
                bytes = Base64.getDecoder().decode(image.getB64Json());
            } else if (image.getUrl() != null && !image.getUrl().isBlank()) {
                bytes = download(image.getUrl());
            } else {
                return "ERROR: the image model returned neither b64_json nor a url.";
            }

            String url = storage.store(bytes, "png");
            collector.add(url);
            record("image", bytes.length, sample);
            // The URL is deliberately NOT in the return value any more: the
            // model kept reformatting it, and the UI reads the collector.
            return "Done — the picture has been created and shown to the user.";

        } catch (RuntimeException e) {
            log.error("[MM] generate_image failed: {}", e.getMessage());
            record("image", 0, sample);
            return "ERROR: could not generate the image — " + e.getMessage();
        }
    }

    @Tool(description = """
            Read a piece of text out loud and save it as an MP3.
            Use this when the user asks to hear, say, speak or narrate
            something. The player appears for the user automatically — just
            confirm what you read. Keep the text under a few hundred words.
            """)
    public String speak_text(
            @ToolParam(description = "The exact text to read out loud, in the language it is written in")
            String text) {

        log.info("[MM] speak_text: {} chars", text == null ? 0 : text.length());
        Timer.Sample sample = Timer.start(registry);
        try {
            byte[] mp3 = speechModel.call(text);
            String url = storage.store(mp3, "mp3");
            collector.add(url);
            record("speech", mp3.length, sample);
            return "Done — the audio has been generated and is playing for the user.";

        } catch (RuntimeException e) {
            log.error("[MM] speak_text failed: {}", e.getMessage());
            record("speech", 0, sample);
            return "ERROR: could not synthesize speech — " + e.getMessage();
        }
    }

    /**
     * Per-modality cost signal.
     *
     * gen_ai_client_token_usage only ever describes the CHAT leg. An image is
     * billed per picture, speech per character, transcription per second of
     * audio — so counting tokens reports zero for the most expensive calls in
     * this module. `size` is a proxy for volume, not for price; the honest
     * headline is the count, sliced by modality.
     */
    private void record(String modality, int bytes, Timer.Sample sample) {
        sample.stop(Timer.builder("mm.generation.duration")
                .description("Duration of a non-chat generation call")
                .tag("modality", modality)
                .tag("framework", "spring-ai")
                .register(registry));

        registry.counter("mm.generation.calls",
                "modality", modality, "framework", "spring-ai").increment();

        if (bytes > 0) {
            registry.summary("mm.generation.size",
                    "modality", modality, "framework", "spring-ai").record(bytes);
        }
    }

    private static byte[] download(String url) {
        try (var in = java.net.URI.create(url).toURL().openStream()) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("Cannot download generated image: " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
