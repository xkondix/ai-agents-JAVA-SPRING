package com.xkondix.multimodal.tools;

import com.xkondix.multimodal.media.Artifact;
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

import java.nio.charset.StandardCharsets;
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
 * ── EVERY TOOL CHECKS THE RETRY BUDGET. EVERY ONE. ─────────────────────────
 *
 * ToolCallingAdvisor has no failure semantics: a tool that answers "ERROR: …"
 * has, as far as the loop is concerned, answered. The model reads the error,
 * decides to try again, and nothing stops it. Observed on 2026-09-15: one
 * request produced OVER A HUNDRED calls to generate_music in a single turn.
 *
 * The budget was added to MusicTools and VideoTools first because those are
 * the expensive ones — and that was a mistake worth recording. The loop does
 * not care which tool is cheap. A hundred failing generate_image calls is
 * still a hundred image requests, and a hundred failing analyze_image calls
 * is a hundred vision calls; neither is free, and both fail in exactly the
 * same silent-until-the-invoice way. Protecting only the tools whose price
 * you happen to notice is not protection, it is luck.
 *
 * So the check is now in all five tools, before any work happens.
 *
 * TOOLS RETURN A SENTENCE, NEVER THE BYTES AND NO LONGER EVEN THE URL. A tool
 * result goes back into the conversation, so returning base64 would push
 * megabytes into the next prompt — paid per token, repeated every turn, and
 * copied into span attributes and Loki where it silently exceeds the limits.
 * The URL was dropped too, once it became clear the model rewrites it (see
 * MediaCollector); the interface reads the collector instead.
 *
 * ── TWO API NOTES FOR SPRING AI 2.0 ────────────────────────────────────────
 *
 * 1. TTS WAS RENAMED. org.springframework.ai.openai.audio.speech.* moved to
 *    org.springframework.ai.audio.tts.*, SpeechModel became
 *    TextToSpeechModel, and `speed` went from Float to Double.
 *
 * 2. THE IMAGE RESULT IS EITHER A URL OR BASE64, DEPENDING ON THE MODEL.
 *    Older OpenAI models answer with a temporary URL; gpt-image-* answers
 *    with b64Json and no URL at all. Both are handled, and a remote URL is
 *    downloaded immediately so the artifact outlives the provider's expiry.
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

            If this returns an ERROR, do NOT call it again in the same turn.
            """)
    public String analyze_image(
            @ToolParam(description = "The image URL from the conversation, e.g. /api/v1/multimodal/media/2026-09-05/abc.png")
            String image_url,
            @ToolParam(description = "What you want to know about the picture", required = false)
            String question) {

        String refusal = refuseIfBudgetSpent("analyze_image", "look at images");
        if (refusal != null) {
            return refusal;
        }
        try {
            return visionAgent.analyze(image_url, question);
        } catch (RuntimeException e) {
            collector.recordFailure();
            log.error("[MM] analyze_image failed: {}", e.getMessage());
            return "ERROR: could not analyze the image — " + e.getMessage()
                    + ". Do NOT retry; tell the user this part failed.";
        }
    }

    @Tool(description = """
            Generate a picture from a text description and save it.
            Use this whenever the user asks to draw, illustrate, visualise or
            picture something. The picture is shown to the user automatically —
            just say what you drew.

            Each attempt costs money. If this returns an ERROR, do NOT call it
            again in the same turn.
            """)
    public String generate_image(
            @ToolParam(description = "Detailed English description of the picture to draw")
            String description) {

        String refusal = refuseIfBudgetSpent("generate_image", "generate pictures");
        if (refusal != null) {
            return refusal;
        }

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
                // Counts as a failure: the call was made and billed, it just
                // came back in a shape we cannot use.
                collector.recordFailure();
                record("image", 0, sample);
                return "ERROR: the image model returned neither b64_json nor a url. Do NOT retry.";
            }

            String url = storage.store(bytes, "png");
            collector.add(Artifact.of(url, Artifact.Kind.IMAGE, description));
            record("image", bytes.length, sample);
            return "Done — the picture has been created and shown to the user.";

        } catch (RuntimeException e) {
            int failures = collector.recordFailure();
            log.error("[MM] generate_image failed ({}/{}): {}",
                    failures, MediaCollector.MAX_FAILURES, e.getMessage());
            record("image", 0, sample);
            return "ERROR: could not generate the image — " + e.getMessage()
                    + ". Do NOT retry; tell the user this part failed.";
        }
    }

    @Tool(description = """
            Read a piece of text out loud and save it as an MP3.
            Use this when the user asks to hear, say, speak or narrate
            something. This is a NARRATING VOICE, not music — for a song or a
            melody use generate_music instead. The player appears for the user
            automatically; just confirm what you read. Keep the text under a
            few hundred words.

            If this returns an ERROR, do NOT call it again in the same turn.
            """)
    public String speak_text(
            @ToolParam(description = "The exact text to read out loud, in the language it is written in")
            String text) {

        String refusal = refuseIfBudgetSpent("speak_text", "read text out loud");
        if (refusal != null) {
            return refusal;
        }

        log.info("[MM] speak_text: {} chars", text == null ? 0 : text.length());
        Timer.Sample sample = Timer.start(registry);
        try {
            byte[] mp3 = speechModel.call(text);
            String url = storage.store(mp3, "mp3");

            // The script is stored as its own artifact. When narration comes
            // out wrong it is the only way to tell a bad voice from a bad
            // script — and the model writes this text itself, so it is not
            // visible anywhere else.
            String scriptUrl = storage.store(text.getBytes(StandardCharsets.UTF_8), "txt");
            collector.add(Artifact.of(scriptUrl, Artifact.Kind.TEXT, abbreviate(text)));
            collector.add(Artifact.of(url, Artifact.Kind.SPEECH, abbreviate(text)));

            record("speech", mp3.length, sample);
            return "Done — the audio has been generated and is playing for the user.";

        } catch (RuntimeException e) {
            int failures = collector.recordFailure();
            log.error("[MM] speak_text failed ({}/{}): {}",
                    failures, MediaCollector.MAX_FAILURES, e.getMessage());
            record("speech", 0, sample);
            return "ERROR: could not synthesize speech — " + e.getMessage()
                    + ". Do NOT retry; tell the user this part failed.";
        }
    }

    /**
     * The one stop condition the framework does not provide.
     *
     * @return the refusal to hand back to the model, or null to proceed
     */
    private String refuseIfBudgetSpent(String tool, String capability) {
        if (!collector.budgetExhausted()) {
            return null;
        }
        log.warn("[MM] {} refused — retry budget spent for this request", tool);
        return "ERROR: generation already failed " + MediaCollector.MAX_FAILURES
                + " times in this turn. STOP calling tools and tell the user you "
                + "cannot " + capability + " right now.";
    }

    /**
     * Per-modality cost signal.
     *
     * gen_ai_client_token_usage only ever describes the CHAT leg. An image is
     * billed per picture, speech per character, a Lyria song at a flat $0.08 —
     * so counting tokens reports zero for the most expensive calls in this
     * module. `size` is a proxy for volume, not price; the honest headline is
     * the count, sliced by modality.
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
