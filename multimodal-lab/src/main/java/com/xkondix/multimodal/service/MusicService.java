package com.xkondix.multimodal.service;

import com.xkondix.multimodal.media.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Music generation with Google Lyria 3, through OpenRouter.
 *
 * ── THE ONE MODALITY THAT DROPPED OUT OF THE ABSTRACTION ───────────────────
 *
 * Chat, vision, images, speech and transcription all go through Spring AI.
 * Music does not, and the reason is worth the whole slide it will get.
 *
 * Five attempts, five correct error messages, no path:
 *
 *   1. chatModel.call() → the TIMED LYRICS, no audio:
 *          [0.0:7.3] O-Zone, we dance tonight,
 *      Google's docs say the response carries lyrics "alongside the audio",
 *      so this read as proof the audio was elsewhere in the message.
 *   2. Read AssistantMessage.getMedia() as well → media blocks=0.
 *   3. Realised the request never asked for audio. A chat completion returns
 *      it only with modalities + an audio parameter, and Spring AI does not
 *      add those for you — correctly, since for every other chat model they
 *      would be wrong. Without them Lyria still composes, still bills, and
 *      returns the words of a song nobody can hear.
 *   4. Added them with a null voice → "`voice` is required, but was not set",
 *      thrown locally by the OpenAI SDK. A music model has no use for a
 *      voice; the field exists because the envelope was designed for speech,
 *      and the SDK validates the envelope, not the model.
 *   5. Added a voice → "400: Audio output requires stream: true". So switched
 *      to chatModel.stream() → the stream arrives, the lyrics arrive, and
 *      media blocks=0 again.
 *
 * The last one is the wall. OpenAiChatModel maps audio into Media only in the
 * NON-STREAMING path (OpenAiChatModel:385 reads message.audio() off a
 * completed message); the streaming path builds generations from `delta`,
 * which has no audio mapping. The framework supports audio output. The
 * provider supports audio output. The intersection is empty.
 *
 * NOTHING HERE WAS A BUG. Four designs — Google's Interactions API,
 * OpenRouter's streaming rule, the OpenAI SDK's envelope validation, Spring
 * AI's response mapping — each correct on its own terms, composing into
 * something that cannot work. No layer could warn, because no layer was
 * wrong. That is a sharper version of this project's argument than any of the
 * silent failures in the catalogue.
 *
 * So music talks raw HTTP through LyriaClient. One modality on the transport
 * is a smaller price than pretending the abstraction covers five when it
 * covers four.
 *
 * ── COST, AND WHY THE METRICS MATTER MORE HERE ─────────────────────────────
 *
 * $0.08 per song, $0.04 per clip, flat, regardless of prompt length — roughly
 * a thousand times a chat call. Bypassing ChatClient also means no `gen_ai`
 * span and no token metrics for this call, so mm.generation.* is not a
 * supplement here, it is the ONLY accounting.
 *
 * Attempts 1 and 2 above were billed in full, and see MediaCollector for why
 * a retry budget had to be added after one request produced a hundred failed
 * calls in a single turn.
 *
 * ── ONE THING THAT IS NOT OUR PROBLEM ──────────────────────────────────────
 *
 * "Gemini blocked the request: PROHIBITED_CONTENT" comes back for prompts that
 * name a real artist to imitate ("in the style of Tede"). Describe the genre
 * instead. The error is reported inside a 200 stream, which LyriaClient
 * unwraps so the user sees a reason rather than an empty result.
 */
@Slf4j
@Service
public class MusicService {

    private final LyriaClient lyria;
    private final MediaStorage storage;
    private final MeterRegistry registry;
    private final String proModel;
    private final String clipModel;

    /** What Lyria composed, so the caller can keep the timed lyrics. */
    public record Composition(String url, String lyrics) {}

    public MusicService(LyriaClient lyria,
                        MediaStorage storage,
                        MeterRegistry registry,
                        @Value("${multimodal.music.model-pro:google/lyria-3-pro-preview}") String proModel,
                        @Value("${multimodal.music.model-clip:google/lyria-3-clip-preview}") String clipModel) {
        this.lyria = lyria;
        this.storage = storage;
        this.registry = registry;
        this.proModel = proModel;
        this.clipModel = clipModel;
    }

    /**
     * @param fullSong true → Lyria Pro (structured song, ~minutes, $0.08);
     *                 false → Lyria Clip (30 s loop or jingle, $0.04)
     */
    public Composition generate(String description, String lyrics, boolean fullSong) {
        String model = fullSong ? proModel : clipModel;
        String prompt = lyrics == null || lyrics.isBlank()
                ? description
                : description + "\n\nUse these lyrics:\n" + lyrics;

        log.info("[MUSIC] model={} fullSong={} prompt={}", model, fullSong, abbreviate(prompt));
        Timer.Sample sample = Timer.start(registry);
        try {
            LyriaClient.Result result = lyria.generate(model, prompt);

            String url = storage.store(result.audio(), "mp3");
            record(result.audio().length, sample);
            log.info("[MUSIC] stored {} ({} bytes)", url, result.audio().length);

            // The text is the timed lyrics Lyria actually sang — it adapts the
            // words to the melody, so this is more useful than what we sent.
            return new Composition(url, result.lyrics());

        } catch (RuntimeException e) {
            record(0, sample);
            throw e;
        }
    }

    /**
     * Per-modality cost signal, and for music the only one that exists — see
     * the class comment.
     */
    private void record(int bytes, Timer.Sample sample) {
        sample.stop(Timer.builder("mm.generation.duration")
                .description("Duration of a non-chat generation call")
                .tag("modality", "music")
                .tag("framework", "spring-ai")
                .register(registry));
        registry.counter("mm.generation.calls",
                "modality", "music", "framework", "spring-ai").increment();
        if (bytes > 0) {
            registry.summary("mm.generation.size",
                    "modality", "music", "framework", "spring-ai").record(bytes);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 160 ? text : text.substring(0, 160) + "…";
    }
}
