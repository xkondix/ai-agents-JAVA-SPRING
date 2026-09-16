package com.xkondix.multimodal.tools;

import com.xkondix.multimodal.media.Artifact;
import com.xkondix.multimodal.media.MediaCollector;
import com.xkondix.multimodal.media.MediaStorage;
import com.xkondix.multimodal.service.VideoClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Video as a tool — the most expensive thing the router can choose.
 *
 * ── OFF BY DEFAULT, AND THAT IS THE POINT ──────────────────────────────────
 *
 * Measured on 2026-09-15: a four-second 720p clip from Veo 3.1 cost $1.60 and
 * took 80 seconds. That is forty Lyria clips, or several thousand router
 * calls. Everything else in this module can be left switched on and poked at
 * during a demo without thinking; this cannot.
 *
 * So the bean only exists when multimodal.video.enabled=true, and when it does
 * not exist MultimodalAgentService leaves the tool out of the prompt entirely.
 * A disabled tool that the model can still see is worse than no tool: it gets
 * offered to the user, called, and fails — spending a round trip to discover
 * something the configuration already knew.
 *
 * ── THE PRICE IS THE DESIGN CONSTRAINT ─────────────────────────────────────
 *
 * The defaults are the cheapest usable ones — four seconds at 720p — and the
 * tool description says what that costs, because the MODEL is the one
 * choosing. A model that decides to "try a longer version" spends another two
 * dollars; a retry loop spends ten, which is why the MediaCollector budget is
 * checked before any work happens here.
 *
 * Worth saying on the cost slide: the router is a $0.0001 model making $2
 * decisions. Nothing in the tool-calling contract expresses that — price is
 * not part of any schema. It lives in the description, where it is advice
 * rather than a rule, and the only real limits are this flag and the budget.
 *
 * ── WHY THE PARAMETERS ARE NARROW ──────────────────────────────────────────
 *
 * Durations are per model: Veo accepts 4, 6 or 8 seconds, Wan accepts 5 or
 * 10. An unsupported value returns a 400 listing the valid ones — helpful for
 * a human, useless for an agent that already spent the round trip. So the
 * tool exposes a small, safe set and clamps anything else.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "multimodal.video.enabled", havingValue = "true")
public class VideoTools {

    private final VideoClient video;
    private final MediaStorage storage;
    private final MediaCollector collector;
    private final MeterRegistry registry;
    private final String model;
    private final String resolution;

    public VideoTools(VideoClient video,
                      MediaStorage storage,
                      MediaCollector collector,
                      MeterRegistry registry,
                      @Value("${multimodal.video.model:google/veo-3.1}") String model,
                      @Value("${multimodal.video.resolution:720p}") String resolution) {
        this.video = video;
        this.storage = storage;
        this.collector = collector;
        this.registry = registry;
        this.model = model;
        this.resolution = resolution;
        log.warn("[MM] VIDEO GENERATION IS ENABLED — {} at {}, roughly $0.40 per second",
                model, resolution);
    }

    @Tool(description = """
            Generate a short VIDEO clip from a description. Use this only when
            the user explicitly asks for a video, a clip, an animation or
            something moving — a still picture is generate_image and is a
            thousand times cheaper.

            THIS IS EXPENSIVE: about $1.60 for four seconds, measured. Keep
            duration at 4 unless the user insists on longer, and never call it
            twice for the same request.

            Generation takes one to three minutes. The player appears for the
            user automatically; just say what you made.
            """)
    public String generate_video(
            @ToolParam(description = """
                    Detailed English description of the clip: subject, motion,
                    camera movement, lighting, mood. Video models reward
                    specificity about movement more than about appearance.""")
            String description,

            @ToolParam(description = "Length in seconds: 4, 6 or 8. Use 4 unless asked otherwise.",
                    required = false)
            Integer duration_seconds,

            @ToolParam(description = "Aspect ratio: 16:9 for landscape, 9:16 for vertical, 1:1 for square.",
                    required = false)
            String aspect_ratio) {

        if (collector.budgetExhausted()) {
            log.warn("[MM] generate_video refused — retry budget spent for this request");
            return "ERROR: generation already failed "
                    + MediaCollector.MAX_FAILURES + " times in this turn. "
                    + "STOP calling this tool and tell the user video is unavailable right now.";
        }

        int duration = clampDuration(duration_seconds);
        String ratio = aspect_ratio == null || aspect_ratio.isBlank() ? "16:9" : aspect_ratio;

        log.info("[MM] generate_video: {}s {} {} — {}", duration, resolution, ratio,
                abbreviate(description));

        Timer.Sample sample = Timer.start(registry);
        try {
            VideoClient.Result result =
                    video.generate(model, description, duration, resolution, ratio);

            String url = storage.store(result.video(), "mp4");
            collector.add(Artifact.of(url, Artifact.Kind.VIDEO, description));
            record(result.video().length, result.cost(), sample);

            return "Done — the " + duration + "-second clip has been generated "
                    + "and is playing for the user.";

        } catch (RuntimeException e) {
            int failures = collector.recordFailure();
            log.error("[MM] generate_video failed ({}/{}): {}",
                    failures, MediaCollector.MAX_FAILURES, e.getMessage());
            record(0, 0.0, sample);
            return "ERROR: could not generate the video — " + e.getMessage()
                    + ". Do NOT retry; tell the user this part failed.";
        }
    }

    /** Veo accepts 4, 6 or 8. Anything else is a 400 and a wasted round trip. */
    private static int clampDuration(Integer requested) {
        if (requested == null) {
            return 4;
        }
        if (requested <= 4) {
            return 4;
        }
        return requested <= 6 ? 6 : 8;
    }

    /**
     * Video is the only modality where the provider tells us the REAL price
     * rather than leaving us to estimate it, so that figure is recorded
     * verbatim. Everything else in mm.generation.* is a count and a duration;
     * this one is money — $1.60 on the first real run.
     */
    private void record(int bytes, double cost, Timer.Sample sample) {
        sample.stop(Timer.builder("mm.generation.duration")
                .description("Duration of a non-chat generation call")
                .tag("modality", "video")
                .tag("framework", "spring-ai")
                .register(registry));

        registry.counter("mm.generation.calls",
                "modality", "video", "framework", "spring-ai").increment();

        if (bytes > 0) {
            registry.summary("mm.generation.size",
                    "modality", "video", "framework", "spring-ai").record(bytes);
        }
        if (cost > 0) {
            registry.summary("mm.generation.cost.usd",
                    "modality", "video", "framework", "spring-ai").record(cost);
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 120 ? text : text.substring(0, 120) + "…";
    }
}
