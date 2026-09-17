package com.xkondix.multimodal.media;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Collects the artifacts produced during one request — and counts the
 * failures, which turned out to matter more.
 *
 * ── WHY THE ARTIFACT LIST EXISTS ───────────────────────────────────────────
 *
 * The first version asked the model to quote each tool's URL verbatim and
 * scraped the answer with a regex. Two runs, two different inventions:
 *
 *     ![Cat](https://api.v1/multimodal/media/…png)
 *     ![GKS Stadium](attachment://stadion_gks_katowice.png)
 *
 * Invented hosts, invented schemes, invented filenames, wrapped in markdown.
 * Both times the real file was on disk and the UI showed nothing. AN
 * INSTRUCTION IS NOT A CONTRACT: anything the interface depends on has to be
 * produced by code, not requested in a prompt.
 *
 * ── WHY THE FAILURE COUNTER EXISTS ─────────────────────────────────────────
 *
 * ToolCallingAdvisor loops until the model stops asking for tools. That is the
 * feature — chaining for free — and it has no failure semantics: a tool that
 * answers "ERROR: …" has, as far as the loop is concerned, answered. The model
 * reads the error, decides to try again, and the loop happily obliges.
 *
 * Observed on 2026-09-15: one request to generate music produced OVER A
 * HUNDRED calls to generate_music in a single turn, each failing on the same
 * validation error, each round trip billed as a chat call. It only stopped
 * because the model eventually gave up.
 *
 * That failure happened to be local and free. A transient 500 from an image or
 * music provider would not be: at $0.04 per Lyria clip, a hundred retries is
 * four dollars of nothing, generated in under a minute with no rate limit and
 * no error anywhere that says "this is looping".
 *
 * So the loop needs a stop condition that the framework does not provide. The
 * tools ask this counter before working and return a TERMINAL message once the
 * budget is spent — one the model is told not to retry. The counter resets per
 * request, alongside the artifact list.
 */
@Component
public class MediaCollector {

    /** Failed generation attempts allowed per request, across all tools. */
    public static final int MAX_FAILURES = 2;

    private static final ThreadLocal<List<Artifact>> ARTIFACTS =
            ThreadLocal.withInitial(ArrayList::new);
    private static final ThreadLocal<AtomicInteger> FAILURES =
            ThreadLocal.withInitial(AtomicInteger::new);

    /** Call at the start of a request — a thread is reused across requests. */
    public void start() {
        ARTIFACTS.get().clear();
        FAILURES.get().set(0);
    }

    /** Called by the tools with what they just produced. */
    public void add(Artifact artifact) {
        ARTIFACTS.get().add(artifact);
    }

    public List<Artifact> collected() {
        return List.copyOf(ARTIFACTS.get());
    }

    /** Just the playable/viewable URLs, in production order, for the reply. */
    public List<String> urls() {
        return ARTIFACTS.get().stream()
                .filter(a -> a.kind() != Artifact.Kind.TEXT)
                .map(Artifact::url)
                .toList();
    }

    /** @return true once this request has burned its retry budget. */
    public boolean budgetExhausted() {
        return FAILURES.get().get() >= MAX_FAILURES;
    }

    public int recordFailure() {
        return FAILURES.get().incrementAndGet();
    }

    /**
     * Removes the entries entirely rather than just clearing them: with a
     * pooled or virtual carrier, a stale ThreadLocal is a slow leak that only
     * shows up under load.
     */
    public void clear() {
        ARTIFACTS.remove();
        FAILURES.remove();
    }
}
