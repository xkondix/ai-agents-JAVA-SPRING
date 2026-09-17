package com.xkondix.multimodal.service;

import com.xkondix.multimodal.media.MediaStorage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.content.Media;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.nio.file.Path;

/**
 * The vision leg — a second agent with its own prompt and its own model.
 *
 * WHY THIS IS NOT PART OF THE ROUTER. The router could have taken the image
 * itself: attach it as Media to the user message and let one model do
 * everything. That works, and it was the first version of this module. It
 * was replaced because it costs more than it looks:
 *
 *   - EVERY request then has to go to a vision-capable model, including
 *     "what time is it". The router is supposed to be the cheap one.
 *   - The trace flattens. One span, no delegation, nothing showing that a
 *     picture was involved.
 *   - You cannot give the vision leg its own instructions without polluting
 *     the router's system prompt with rules that apply to one modality.
 *
 * Delegating instead gives a nested span (chat → tool_call analyze_image →
 * chat), a separate model per capability, and a prompt that only talks about
 * looking at pictures.
 *
 * ── THE PRICE, DEMONSTRATED LIVE ───────────────────────────────────────────
 *
 * The router never sees the pixels — only this agent's written description.
 * Two consequences, both observed:
 *
 *   1. Ask "how many players are there", then "what colour are the shirts",
 *      and the second question needs a SECOND vision call, because the first
 *      description did not happen to mention colour.
 *
 *   2. THE ROUTER CANNOT CHECK THE ANSWER. On 2026-09-16 a user uploaded a
 *      tic-tac-toe board and asked for the positions. The vision model
 *      counted correctly (3 and 3) and placed them wrong. The user said so,
 *      the router called analyze_image again, got a DIFFERENT wrong answer,
 *      and presented it with the same confidence. It had no way to know: it
 *      has never seen the board, and a description is not something you can
 *      verify against itself.
 *
 * That is the honest cost of delegation, and it is worth showing rather than
 * hiding. It also sets the boundary of the design: this architecture is right
 * for "tell me what is in this", and wrong for anything where the router
 * needs to reason about the image itself.
 *
 * ── MODEL CHOICE IS THE REAL FIX FOR (2) ───────────────────────────────────
 *
 * The default is gpt-4o-mini, the cheapest option, and small vision models
 * are specifically weak at SPATIAL reasoning — which object is where, in what
 * row, relative to what else. They recognise and count reliably and then
 * invent coordinates. The prompt below pushes back on that, but a prompt
 * cannot add a capability the model lacks; if the demo involves grids,
 * diagrams, seating plans or formations, set:
 *
 *     OPENROUTER_VISION_MODEL=openai/gpt-4o
 *
 * Nothing else changes. That is the whole point of giving this leg its own
 * model: you pay for the better one only on the calls that look at pictures.
 */
@Slf4j
@Service
public class VisionAgent {

    /**
     * The scanning rules exist because of the tic-tac-toe run above.
     *
     * Asking for "concrete and complete" gets a confident narrative. A small
     * vision model will describe a grid fluently and place the contents
     * wrongly, because nothing forced it to go cell by cell. Making the ORDER
     * of inspection explicit — and asking it to say the layout out loud before
     * answering — turns free-form description into something closer to
     * reading, which is measurably better at this class of question.
     *
     * The last rule matters most for the caller: this agent's output is the
     * only thing the router will ever know, so an uncertain answer that says
     * it is uncertain is far more useful than a confident wrong one. The
     * router cannot tell them apart otherwise.
     */
    private static final String SYSTEM = """
            You describe images for another agent that cannot see them.

            Be concrete and complete: objects, people, counts, text visible in
            the picture, colours, layout. If the caller asked a specific
            question, answer it first and then add the context that a follow-up
            question would probably need.

            When the image has a GRID, TABLE, BOARD or any regular layout:
              - first state the size out loud, e.g. "a 3x3 grid".
              - then go cell by cell in a fixed order: row 1 left to right,
                then row 2, then row 3. Name every cell, including the empty
                ones ("row 2, column 1: empty").
              - only after that give counts or summaries. Do not describe
                positions from memory of the picture as a whole.

            When asked where something is, answer in the caller's coordinate
            system (row and column, counted from 1 at the top left) and say
            which corner you counted from.

            Never speculate about what is not visible. If something is
            unreadable, ambiguous, or you are unsure of a position, SAY SO
            explicitly — the agent reading this cannot see the image and has
            no way to check you. A hedged answer is useful; a confident wrong
            one is not.
            """;

    private final ChatClient vision;
    private final MediaStorage storage;
    private final MeterRegistry registry;
    private final String model;

    public VisionAgent(ChatClient.Builder builder,
                       MediaStorage storage,
                       MeterRegistry registry,
                       @Value("${multimodal.vision-model:openai/gpt-4o-mini}")
                       String model) {
        // Injected builder, never ChatClient.builder(chatModel): the static
        // factory hands the client an ObservationRegistry.NOOP and every span
        // and metric below the call disappears, silently.
        this.vision = builder.defaultSystem(SYSTEM).build();
        this.storage = storage;
        this.registry = registry;
        this.model = model;
        log.info("[MM] vision leg on {}", model);
    }

    /**
     * @param imageUrl the public media path the upload was stored under
     * @param question what the router wants to know; may be null
     */
    public String analyze(String imageUrl, String question) {
        String relative = imageUrl.replace(MediaStorage.URL_PREFIX, "");
        Path file = storage.resolve(relative);
        log.info("[MM] analyze_image {} question='{}'", relative, question);

        Timer.Sample sample = Timer.start(registry);
        try {
            MimeType mime = relative.endsWith(".png")
                    ? MimeTypeUtils.IMAGE_PNG : MimeTypeUtils.IMAGE_JPEG;

            String answer = vision.prompt()
                    // Per-call model override so the router can stay cheap:
                    // this is the only place a vision-capable model is needed.
                    //
                    // NOTE the missing .build(). In Spring AI 2.0 options()
                    // takes a ChatOptions.Builder, not a built ChatOptions —
                    // passing the finished object fails with "inference
                    // variable B has incompatible bounds", which reads like a
                    // generics puzzle and is really just one call too many.
                    .options(OpenAiChatOptions.builder().model(model))
                    .user(u -> u
                            .text(question == null || question.isBlank()
                                    ? "Describe this image."
                                    : question)
                            .media(new Media(mime, new FileSystemResource(file))))
                    .call()
                    .content();

            record(sample);
            return answer;

        } catch (RuntimeException e) {
            log.error("[MM] analyze_image failed: {}", e.getMessage());
            record(sample);
            throw e;
        }
    }

    private void record(Timer.Sample sample) {
        sample.stop(Timer.builder("mm.generation.duration")
                .description("Duration of a non-chat generation call")
                .tag("modality", "vision")
                .tag("framework", "spring-ai")
                .register(registry));
        registry.counter("mm.generation.calls",
                "modality", "vision", "framework", "spring-ai").increment();
    }
}
