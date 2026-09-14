package com.xkondix.multimodal.service;

import com.xkondix.multimodal.tools.MultimodalTools;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;

/**
 * The router: one cheap agent that never sees a pixel or a byte.
 *
 * Every capability is a tool, so the routing logic IS the system prompt.
 * There is no classifier and no switch — and no loop either: Spring AI 2.0
 * runs tool execution inside ToolCallingAdvisor, which keeps calling the
 * model until it stops asking for tools. A request like "here is a photo of
 * the 2007 squad, draw a modern kit for them and read the caption out loud"
 * turns into analyze_image → generate_image → speak_text, three round trips
 * this class knows nothing about.
 *
 * Worth putting next to patterns-spring-ai on a slide: the same iteration,
 * but there the loop is hand-written because WE own the plan, and here it
 * belongs to the framework because the MODEL owns the plan. That is the real
 * line between orchestrator-workers and tool calling — who decides the
 * sequence, not which API is used.
 *
 * ── WHAT ENTERS HOW ────────────────────────────────────────────────────────
 *
 *   image IN   → stored on disk, its URL mentioned in the message; the router
 *                calls analyze_image when it needs to know what is in it
 *   audio IN   → transcribed BEFORE the router runs; the transcript is the
 *                question
 *   image OUT  → tool generate_image
 *   speech OUT → tool speak_text
 *
 * Transcription is the one thing that cannot be a tool: a voice note IS the
 * question, so the model would have to know its contents in order to decide
 * to read it.
 *
 * MEMORY IS IN REDIS. Conversations here reference files on disk, so an
 * in-memory window loses the thread while the artifacts survive it — the
 * worst of both. The ChatMemory bean is auto-configured by
 * spring-ai-starter-model-chat-memory-repository-redis; this class only
 * points at a conversation id and offers a way to wipe one.
 *
 * ── THE PROMPT DOES NOT ASK FOR URLs ANY MORE ──────────────────────────────
 *
 * It used to say "repeat the tool's URL verbatim, the interface turns it into
 * a picture". Two runs, two different inventions:
 *
 *     ![Cat](https://api.v1/multimodal/media/…png)        — invented host
 *     ![GKS Stadium](attachment://stadion_gks.png)        — invented scheme
 *                                                            AND filename
 *
 * Both times the real file was on disk and the artifact rendered correctly
 * from MediaCollector — the markdown was pure noise sitting above the picture
 * it was pretending to be.
 *
 * So the rule is inverted: the model is told the artifacts appear on their
 * own and that it must NOT write links or filenames. Asking it to stop doing
 * something is still an instruction rather than a contract, but the cost of
 * it being ignored is now cosmetic instead of a blank UI.
 */
@Slf4j
@Service
public class MultimodalAgentService {

    private static final String SYSTEM = """
            You are a multimodal assistant for an AC Milan demo.

            You cannot see images or hear audio yourself. You have tools:
              - analyze_image  — find out what is in a picture the user attached
              - generate_image — draw something new
              - speak_text     — read text out loud

            Rules:
              - When a message mentions an attached image URL and the answer
                depends on what the picture shows, CALL analyze_image first.
              - When the user asks for a picture, CALL generate_image. Never
                describe an imaginary picture instead of calling the tool.
              - When the user asks to hear something, CALL speak_text with the
                exact words that should be spoken.
              - Chain tools when the request needs it: look at the photo, then
                draw from what you learned, then narrate the result.
              - Pictures and audio players are attached to your reply
                AUTOMATICALLY. Do NOT write markdown image links, URLs, file
                names or placeholders like ![...](...) — they render as broken
                text above the real thing.
              - Just say in one sentence what you made. The artifacts carry
                the content.
              - If a tool answers with ERROR, say plainly which part failed and
                what still worked. Do not pretend the artifact exists.
              - Answer in the language the user wrote in.
            """;

    private final ChatClient router;
    private final MultimodalTools tools;
    private final ChatMemory chatMemory;

    public MultimodalAgentService(ChatClient.Builder builder,
                                  MultimodalTools tools,
                                  ChatMemory chatMemory) {
        // Injected builder, never ChatClient.builder(chatModel) — the static
        // factory silently substitutes ObservationRegistry.NOOP and removes
        // every span and metric below this call.
        this.router = builder.defaultSystem(SYSTEM).build();
        this.tools = tools;
        this.chatMemory = chatMemory;
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

        return router.prompt()
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(tools)
                .user(message)
                .call()
                .content();
    }

    /**
     * Wipes one conversation. Generated files are deliberately NOT deleted —
     * they are addressable by URL and may already be open in the browser.
     */
    public void reset(String conversationId) {
        chatMemory.clear(conversationId);
        log.info("[MM] memory cleared for conversationId={}", conversationId);
    }
}
