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
 * THE PRICE, STATED OUT LOUD: the router never sees the pixels — only this
 * agent's written description. Ask "how many players are there", then "what
 * colour are the shirts", and the second question needs a SECOND vision call,
 * because the first description did not happen to mention colour. The
 * description is a bottleneck, and that is the honest cost of delegation.
 * Worth showing rather than hiding.
 */
@Slf4j
@Service
public class VisionAgent {

    private static final String SYSTEM = """
            You describe images for another agent that cannot see them.

            Be concrete and complete: objects, people, counts, text visible in
            the picture, colours, layout. If the caller asked a specific
            question, answer it first and then add the context that a follow-up
            question would probably need.

            Never speculate about what is not visible. If something is
            unreadable, say so.
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
