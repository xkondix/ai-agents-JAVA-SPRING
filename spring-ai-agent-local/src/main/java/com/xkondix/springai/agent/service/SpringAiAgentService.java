package com.xkondix.springai.agent.service;

import com.xkondix.springai.agent.advisor.InspectionAdvisor;
import com.xkondix.springai.agent.tools.DemoFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

/**
 * Spring AI agent over local @Tool methods, in three chain configurations.
 *
 * All three send the same question to the same model with the same tools. The
 * only thing that changes is the ADVISOR CHAIN — which is the whole point of
 * this module, and the reason the tile in chat-ui has three variants.
 *
 * ── ORDER DECIDES WHO IS INSIDE THE LOOP ───────────────────────────────────
 *
 * Advisors run in ascending order on the way in and descending on the way
 * out. ToolCallingAdvisor is just another link, so everything with a HIGHER
 * order than it sits INSIDE the loop and runs once per iteration.
 *
 *   MessageChatMemoryAdvisor   HIGHEST_PRECEDENCE + 200   (outside)
 *   ToolCallingAdvisor         HIGHEST_PRECEDENCE + 300
 *   InspectionAdvisor          0                          (inside — see below)
 *
 * HIGHEST_PRECEDENCE is Integer.MIN_VALUE, so a plain 0 is enormously larger
 * than MIN_VALUE + 300. Our advisor therefore runs LAST, which puts it inside
 * the loop and makes it log once per lap. That is counter-intuitive enough to
 * be worth saying out loud: order 0 feels like "first" and means "last" here.
 *
 * It is also useful: inside the loop an advisor sees the request and the
 * response of every iteration and can change either of them. LangChain4j's
 * AiServices reaches every iteration too, but through separate hooks: AI
 * Service listeners observe it (AiServiceRequestIssuedEvent per model call,
 * ToolExecutedEvent per tool execution) and cannot change anything, while
 * chatRequestTransformer(...) rewrites each request before it is sent. One
 * advisor in the chain against several hooks around the loop — that is the
 * contrast this module demonstrates.
 *
 * ── ONE MEMORY ADVISOR PER CALL ────────────────────────────────────────────
 *
 * Every method below adds exactly one MessageChatMemoryAdvisor, and the
 * ChatClient bean in SpringAiConfig registers none. Spring AI removes the
 * duplicates between the loop and memory on its own: with a memory advisor
 * inside the loop, DefaultChatClient switches off ToolCallingAdvisor's
 * internal history, and a memory advisor does not re-add history that is
 * already in the prompt. Nothing stops two memory advisors from writing to
 * the same conversation id, though.
 *
 * An earlier version did exactly that, because SpringAiConfig also
 * registered one through defaultAdvisors(...):
 *
 *   - /chat and /chat/advisors: both advisors sat OUTSIDE the loop, so the
 *     first turn looked normal, but the store kept every question and every
 *     answer twice.
 *   - /chat/memory-in-loop: one advisor outside, one inside the loop, and the
 *     prompt of the second iteration carried the user question TWICE
 *     ("How is the weather in Katowice?", "How is the weather in Katowice?").
 *
 * Part I shows that state on the "Memory Inside && Outside" slide.
 *
 * ── SPRING AI 2.0 NOTES ────────────────────────────────────────────────────
 *
 * CONVERSATION ID MOVED FROM THE ADVISOR TO THE REQUEST.
 * MessageChatMemoryAdvisor.Builder used to carry a conversationId; in 2.0 the
 * builder exposes only order() and scheduler(), and the advisor reads the id
 * from the request context instead. The advisor instance is now stateless and
 * safe to share, whereas the old builder baked one conversation into it.
 *
 * TOOL EXECUTION IS NO LONGER THE MODEL'S JOB. The built-in loop was removed
 * from every ChatModel and lifted into the advisor chain: ChatClient
 * auto-registers a ToolCallingAdvisor on every call, even without static
 * tools, so that tools added at runtime by another advisor still run. Opt out
 * per call with AdvisorParams.toolCallingAdvisorAutoRegister(false), or for
 * every client built from the auto-configured builder with
 * spring.ai.chat.client.tool-calling.enabled=false. Adding one by hand (to
 * change its order or options) is allowed: auto-registration then steps
 * aside, and DefaultChatClient fails only when the chain ends up with more
 * than one ToolAdvisor.
 *
 * ── SPRING AI 2.0.1 NOTES ──────────────────────────────────────────────────
 *
 * TOOL CALL LIMITS. Since 2.0.1 DefaultToolCallingManager caps tool calls per
 * turn: 40 per tool and 150 in total (before 2.0.1 there was no limit at
 * all). Exceeding them ends the loop with a dedicated finish reason by
 * default. Configurable via spring.ai.tools.limits.*.
 *
 * USAGE IS CUMULATIVE. ToolCallingAdvisor now reports token usage summed over
 * every model call in the loop, not just the last one — a ChatResponse read
 * after a tool-calling exchange shows higher numbers than on 2.0.0.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiAgentService {

    private final ChatClient chatClient;
    private final DemoFunctions demoFunctions;
    private final MessageWindowChatMemory chatMemory;

    /**
     * Order that puts the memory advisor INSIDE the loop.
     *
     * Anything above ToolCallingAdvisor.DEFAULT_ORDER (HIGHEST_PRECEDENCE+300)
     * runs per iteration. +400 is the value the reference documentation uses.
     */
    private static final int MEMORY_INSIDE_LOOP = BaseAdvisor.HIGHEST_PRECEDENCE + 400;

    /**
     * 1 — the minimal chain: memory plus the auto-registered ToolCallingAdvisor.
     *
     * Memory sits OUTSIDE the loop (its default order is lower), so it loads
     * the history once before the loop and persists only the final user and
     * assistant messages. Tool requests and tool results never reach the store.
     */
    public String chat(String conversationId, String message) {
        log.info("Spring AI chat [default]: conversationId={}", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(demoFunctions)
                .call()
                .content();
    }

    /**
     * 2 — the same call through a longer chain, to show the pattern.
     *
     * Four advisors, three origins:
     *   InspectionAdvisor        ours, order 0, runs INSIDE the loop
     *   SimpleLoggerAdvisor      built in, dumps request and response at DEBUG
     *   MessageChatMemoryAdvisor built in, outside the loop
     *   ToolCallingAdvisor       nobody wrote it — registered by .tools(...)
     *
     * SimpleLoggerAdvisor logs under its own package, not ours, so it stays
     * silent unless the level is raised:
     *     logging.level.org.springframework.ai.chat.client.advisor: DEBUG
     * An advisor in the chain that produces nothing looks broken; it is usually
     * a logger pointed at a package nobody enabled.
     */
    public String chatWithAdvisors(String conversationId, String message) {
        log.info("Spring AI chat [advisors]: conversationId={}", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(
                        new InspectionAdvisor(),
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(demoFunctions)
                .call()
                .content();
    }

    /**
     * 3 — memory moved INSIDE the loop by raising its order.
     *
     * One line different from (1), and it changes what the conversation store
     * ends up containing: the full tool transcript rather than just the two
     * final messages. On the next turn the model can see which tools were
     * already tried and what they returned.
     *
     * The cost is size. Every tool request and every tool result is persisted,
     * so a chained request writes several times more than the minimal chain —
     * and with a MessageWindowChatMemory of N messages, tool traffic competes
     * with actual conversation for the same N slots.
     *
     * NO DOUBLE WRITES FROM THE LOOP ITSELF: ToolCallingAdvisor keeps its own
     * internal conversation history, which would duplicate everything a memory
     * advisor inside the loop also records. DefaultChatClient detects a memory
     * advisor inside the loop and disables that internal history automatically.
     * It is only when you build a ToolCallingAdvisor by hand that you have to
     * call .disableInternalConversationHistory() yourself.
     *
     * With this single advisor the question reaches the model once per
     * iteration. The duplicated question on the "Memory Inside && Outside"
     * slide came from a second memory advisor that SpringAiConfig used to
     * register as a default — see the class comment.
     */
    public String chatWithMemoryInLoop(String conversationId, String message) {
        log.info("Spring AI chat [memory in loop]: conversationId={}", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .order(MEMORY_INSIDE_LOOP)
                        .build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(demoFunctions)
                .call()
                .content();
    }
}
