package com.xkondix.multimodal.media;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects the artifacts produced during one request.
 *
 * WHY THIS EXISTS. The first version asked the model to quote the tool's URL
 * verbatim and then scraped the answer with a regex. The system prompt says
 * "repeat that URL verbatim", and on the very first real run gpt-4o-mini
 * answered with:
 *
 *     ![Cat](https://api.v1/multimodal/media/2026-09-05/39caecc8….png)
 *
 * It invented a scheme and a host, dropped the leading slash, and wrapped the
 * whole thing in markdown. The file was on disk and perfectly fine; the UI
 * showed nothing.
 *
 * The lesson generalises past this module: AN INSTRUCTION IS NOT A CONTRACT.
 * Anything the interface depends on has to be produced by code, not requested
 * in a prompt. The model is free to reformat, translate or beautify — and it
 * will, especially once the conversation is in another language.
 *
 * So the tools now report what they created and the controller reads that
 * list. What the model writes about the URL becomes cosmetic.
 *
 * THREAD-LOCAL IS SAFE HERE, with one condition worth stating: the tool calls
 * happen on the SAME thread as the chat call, because ToolCallingAdvisor runs
 * the loop synchronously. Virtual threads do not change that — each request
 * still gets one carrier for its whole life. If this module ever moves to the
 * streaming API, this class has to move with it, because the tool calls would
 * land on reactor threads instead.
 */
@Component
public class MediaCollector {

    private static final ThreadLocal<List<String>> ARTIFACTS =
            ThreadLocal.withInitial(ArrayList::new);

    /** Call at the start of a request — a thread is reused across requests. */
    public void start() {
        ARTIFACTS.get().clear();
    }

    /** Called by the tools with the public URL they just stored. */
    public void add(String url) {
        ARTIFACTS.get().add(url);
    }

    public List<String> collected() {
        return List.copyOf(ARTIFACTS.get());
    }

    /**
     * Removes the entry entirely rather than just clearing the list: with a
     * pooled or virtual carrier, a stale ThreadLocal is a slow leak that only
     * shows up under load.
     */
    public void clear() {
        ARTIFACTS.remove();
    }
}
