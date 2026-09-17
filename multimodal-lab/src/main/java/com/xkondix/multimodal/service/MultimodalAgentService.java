package com.xkondix.multimodal.service;

import com.xkondix.multimodal.tools.MultimodalTools;
import com.xkondix.multimodal.tools.MusicTools;
import com.xkondix.multimodal.tools.VideoTools;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * The router: one cheap agent that never sees a pixel or a byte.
 *
 * Every capability is a tool, so the routing logic IS the system prompt.
 * There is no classifier and no switch — and no loop either: Spring AI 2.0
 * runs tool execution inside ToolCallingAdvisor, which keeps calling the
 * model until it stops asking for tools. "Here is a photo of the 2007 squad,
 * draw a modern kit for them and write a chant about it" turns into
 * analyze_image → generate_image → generate_music, three round trips this
 * class knows nothing about.
 *
 * Worth putting next to patterns-spring-ai on a slide: the same iteration,
 * but there the loop is hand-written because WE own the plan, and here it
 * belongs to the framework because the MODEL owns the plan. That is the real
 * line between orchestrator-workers and tool calling — who decides the
 * sequence, not which API is used.
 *
 * ── FIVE CAPABILITIES, FOUR PROTOCOLS, ONE PROVIDER ────────────────────────
 *
 *   image IN   → stored on disk, its URL mentioned in the message; the router
 *                calls analyze_image when it needs to know what is in it
 *   audio IN   → transcribed BEFORE the router runs; the transcript is the
 *                question
 *   image OUT  → generate_image   — POST /images/generations    (Spring AI)
 *   speech OUT → speak_text       — POST /audio/speech          (Spring AI)
 *   music OUT  → generate_music   — streaming /chat/completions (raw HTTP)
 *   video OUT  → generate_video   — job queue /videos           (raw HTTP)
 *
 * Same vendor, same key, same base URL, four genuinely different protocols: a
 * synchronous JSON call, another with a different body shape, an SSE stream,
 * and a submit/poll/download job queue. "Unified API" is true for billing and
 * routing; in code, every modality still needs its own client, and the
 * abstraction meant to hide that covers two of the four.
 *
 * Transcription is the one thing that cannot be a tool: a voice note IS the
 * question, so the model would have to know its contents in order to decide
 * to read it.
 *
 * ── THE PROMPT IS BUILT, NOT WRITTEN ───────────────────────────────────────
 *
 * Video costs about $1.60 for four seconds, so VideoTools is behind a flag
 * and usually absent. When it is absent the prompt must not mention it: a
 * model told about a tool it does not have will offer it to the user, try to
 * call it, and fail — spending a round trip to discover what the
 * configuration already knew. So the tool list and the rules that describe it
 * are assembled from what actually exists.
 *
 * ── THE PROMPT ALSO TALKS ABOUT MONEY ──────────────────────────────────────
 *
 * The router costs a fraction of a cent per call and can spend two dollars by
 * choosing generate_video. Nothing in the tool-calling contract expresses
 * price — it is not part of any schema — so the only place it can live is the
 * description and these rules, where it is advice rather than a constraint.
 * The hard limits are the feature flag and the retry budget in
 * MediaCollector.
 *
 * ── THE PROMPT DOES NOT ASK FOR URLs ───────────────────────────────────────
 *
 * It used to say "repeat the tool's URL verbatim". Two runs, two inventions:
 *
 *     ![Cat](https://api.v1/multimodal/media/…png)    — invented host
 *     ![GKS Stadium](attachment://stadion_gks.png)    — invented scheme AND
 *                                                       filename
 *
 * Both times the real file was on disk and rendered correctly from
 * MediaCollector. So the rule is inverted: artifacts appear on their own and
 * the model must NOT write links or filenames.
 */
@Slf4j
@Service
public class MultimodalAgentService {

    private static final String BASE_PROMPT = """
            You are a multimodal assistant for an AC Milan demo.

            You cannot see images or hear audio yourself. You have tools:
              - analyze_image  — find out what is in a picture the user attached
              - generate_image — draw a still picture
              - speak_text     — read text out loud in a narrating voice
              - generate_music — compose actual music, with instruments and,
                                 if you write lyrics, singing
            """;

    private static final String VIDEO_TOOL = """
              - generate_video — generate a short moving clip
            """;

    private static final String RULES = """

            Rules:
              - When a message mentions an attached image URL and the answer
                depends on what the picture shows, CALL analyze_image first.
              - When the user asks for a picture, CALL generate_image. Never
                describe an imaginary picture instead of calling the tool.
              - Tell speech from music. "Read this", "say it", "narrate"
                → speak_text. "Song", "chant", "jingle", "anthem", "melody",
                "soundtrack" → generate_music. A chant sung by fans is MUSIC,
                not narration.
              - Chain tools when the request needs it: look at the photo, then
                draw from what you learned, then write a chant about it.
              - Prefer the short, cheap option of any tool. A picture costs
                cents and a 30-second music clip a few cents, but generation
                is never free — do not produce extras nobody asked for.
              - Pictures and players are attached to your reply AUTOMATICALLY.
                Do NOT write markdown image links, URLs, file names or
                placeholders like ![...](...) — they render as broken text
                above the real thing.
              - Just say in one sentence what you made. The artifacts carry
                the content.
              - If a tool answers with ERROR, say plainly which part failed and
                what still worked. Do not pretend the artifact exists, and do
                not retry a tool that told you not to.
              - Answer in the language the user wrote in.
            """;

    private static final String VIDEO_RULES = """
              - Tell a still from a clip. "Picture", "image", "draw", "poster"
                → generate_image. "Video", "clip", "animation", "moving",
                "trailer" → generate_video.
              - VIDEO IS BY FAR THE MOST EXPENSIVE THING YOU CAN DO: about
                $1.60 for four seconds, which is forty music clips. Only ever
                call generate_video when the user explicitly asked for a video,
                never "as well", and never twice in one turn.
            """;

    private final ChatClient router;
    private final MultimodalTools tools;
    private final MusicTools musicTools;
    private final @Nullable VideoTools videoTools;
    private final ChatMemory chatMemory;

    public MultimodalAgentService(ChatClient.Builder builder,
                                  MultimodalTools tools,
                                  MusicTools musicTools,
                                  ObjectProvider<VideoTools> videoToolsProvider,
                                  ChatMemory chatMemory) {
        this.tools = tools;
        this.musicTools = musicTools;
        this.videoTools = videoToolsProvider.getIfAvailable();
        this.chatMemory = chatMemory;

        String system = videoTools == null
                ? BASE_PROMPT + RULES
                : BASE_PROMPT + VIDEO_TOOL + RULES + VIDEO_RULES;

        // Injected builder, never ChatClient.builder(chatModel) — the static
        // factory silently substitutes ObservationRegistry.NOOP and removes
        // every span and metric below this call.
        this.router = builder.defaultSystem(system).build();

        log.info("[MM] router ready — video generation {}",
                videoTools == null ? "DISABLED (multimodal.video.enabled=false)" : "ENABLED");
    }

    /**
     * @param imageUrl media path of a freshly uploaded picture, or null. It is
     *                 announced in the message rather than attached: the
     *                 router decides whether looking at it is worth a vision
     *                 call.
     */
    public String chat(String conversationId, String question, String imageUrl) {
        String message = (imageUrl == null || imageUrl.isBlank())
                ? question
                : question + "\n\n[The user attached an image at " + imageUrl + "]";

        log.info("[MM] router conversationId={} image={} question='{}'",
                conversationId, imageUrl, question);

        // One flat namespace: the model sees four or five tools and has no
        // idea they come from different classes, or that they speak different
        // protocols.
        List<Object> toolBeans = new ArrayList<>(List.of(tools, musicTools));
        if (videoTools != null) {
            toolBeans.add(videoTools);
        }

        return router.prompt()
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(toolBeans.toArray())
                .user(message)
                .call()
                .content();
    }

    /**
     * Wipes one conversation. Generated files and the gallery are deliberately
     * NOT touched — they are addressable by URL and may already be open in
     * another tab. See ArtifactRegistry.
     */
    public void reset(String conversationId) {
        chatMemory.clear(conversationId);
        log.info("[MM] memory cleared for conversationId={}", conversationId);
    }
}
