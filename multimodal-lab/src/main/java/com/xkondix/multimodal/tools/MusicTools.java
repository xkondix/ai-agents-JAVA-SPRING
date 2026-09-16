package com.xkondix.multimodal.tools;

import com.xkondix.multimodal.media.Artifact;
import com.xkondix.multimodal.media.MediaCollector;
import com.xkondix.multimodal.media.MediaStorage;
import com.xkondix.multimodal.service.MusicService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * Music as its own tool, separate from speak_text.
 *
 * WHY NOT ONE "MAKE AUDIO" TOOL. Both produce an mp3, so merging them looks
 * tidy — and would be wrong in two ways. The models sit behind different
 * endpoints (speech vs chat completions), and more importantly the user's
 * intent differs: "przeczytaj mi to" and "zrób do tego piosenkę" are not the
 * same request. A single tool would force the ROUTER to disambiguate them
 * through a parameter rather than through tool choice — and tool selection is
 * the routing mechanism in this module.
 *
 * The price gap makes the separation load-bearing too. Narration costs
 * fractions of a cent; a Lyria Pro song is $0.08 flat. A model that picked the
 * wrong branch of one merged tool would spend 100x more than intended,
 * silently. Two tools mean the trace shows which one was chosen.
 *
 * ── THE RETRY BUDGET IS NOT OPTIONAL ───────────────────────────────────────
 *
 * A tool that returns "ERROR: …" has, as far as ToolCallingAdvisor is
 * concerned, answered successfully. The loop continues, the model reads the
 * error, decides to try again, and nothing stops it. On 2026-09-15 a single
 * request produced over a hundred calls to this method, all failing the same
 * way.
 *
 * That run was free because the failure was local validation. The same loop
 * against a flaky provider costs $0.04 per lap here and $0.08 on Pro. So the
 * budget in MediaCollector is checked BEFORE doing any work, and the message
 * returned once it is spent tells the model plainly to stop — an instruction
 * is not a contract, but combined with a hard refusal to act it is enough.
 *
 * THE LYRICS COME BACK FROM LYRIA, not from the caller. Google returns the
 * words it actually sang, with timings:
 *
 *     [0.0:5.3] Na boisku gramy, AC Milan w sercu,
 *
 * which is more useful than the lyrics that were sent in — the model adapts
 * them to the melody. So the stored text artifact is the RESPONSE, and the
 * request is kept only when nothing came back.
 */
@Slf4j
@Service
public class MusicTools {

    private final MusicService music;
    private final MediaStorage storage;
    private final MediaCollector collector;

    public MusicTools(MusicService music, MediaStorage storage, MediaCollector collector) {
        this.music = music;
        this.storage = storage;
        this.collector = collector;
    }

    @Tool(description = """
            Compose and record an actual piece of MUSIC — with instruments and,
            if you write lyrics, sung vocals. Use this for a song, a jingle, a
            chant, a theme or a soundtrack. Do NOT use speak_text for music:
            that is a narrating voice, not singing.

            Set full_song=false for a 30-second clip, loop or jingle — this is
            the cheap option and the right default.
            Set full_song=true ONLY when the user clearly wants a complete song
            with verses and a chorus; it costs roughly twice as much.

            Each attempt costs real money. If this tool returns an ERROR, do
            NOT call it again in the same turn — tell the user what failed.

            The player appears for the user automatically; just say what you
            made.
            """)
    public String generate_music(
            @ToolParam(description = """
                    English description of the music: genre, mood, instruments,
                    tempo in BPM. Lyria also understands structure markers such as
                    "[0:00 - 0:15] Intro: building drums".""")
            String description,

            @ToolParam(description = """
                    Lyrics to be sung, in any language. Leave empty for an
                    instrumental piece.""", required = false)
            String lyrics,

            @ToolParam(description = "true for a full song, false for a 30-second clip", required = false)
            Boolean full_song) {

        // Checked BEFORE any work: the loop has no failure semantics of its
        // own, so this is the only thing standing between a flaky provider and
        // a hundred billed retries.
        if (collector.budgetExhausted()) {
            log.warn("[MM] generate_music refused — retry budget spent for this request");
            return "ERROR: music generation already failed "
                    + MediaCollector.MAX_FAILURES + " times in this turn. "
                    + "STOP calling this tool and tell the user it is unavailable right now.";
        }

        boolean full = Boolean.TRUE.equals(full_song);
        try {
            MusicService.Composition composition = music.generate(description, lyrics, full);
            collector.add(Artifact.of(composition.url(), Artifact.Kind.MUSIC, description));

            // Lyria returns the words it sang, timed to the track. Keep those
            // rather than what we asked for — when a song comes out wrong,
            // this is how you tell a bad composition from bad words.
            String sung = composition.lyrics() != null && !composition.lyrics().isBlank()
                    ? composition.lyrics()
                    : lyrics;

            if (sung != null && !sung.isBlank()) {
                String lyricsUrl = storage.store(sung.getBytes(StandardCharsets.UTF_8), "txt");
                collector.add(Artifact.of(lyricsUrl, Artifact.Kind.TEXT, "Lyrics: " + description));
            }

            return "Done — the " + (full ? "song" : "clip")
                    + " has been composed and is playing for the user.";

        } catch (RuntimeException e) {
            int failures = collector.recordFailure();
            log.error("[MM] generate_music failed ({}/{}): {}",
                    failures, MediaCollector.MAX_FAILURES, e.getMessage());
            return "ERROR: could not generate music — " + e.getMessage()
                    + ". Do NOT retry; tell the user this part failed.";
        }
    }
}
