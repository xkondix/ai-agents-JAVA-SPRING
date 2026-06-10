package com.xkondix.rawagent.agent;

import com.xkondix.rawagent.model.Message;
import com.xkondix.rawagent.model.ToolCall;
import com.xkondix.rawagent.tools.DemoTools;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * THE AGENT LOOP — pure Java, no framework.
 *
 * This is the heart of every AI agent, regardless of framework.
 * LangChain4j AiServices and Spring AI ChatClient both implement
 * exactly this loop under the hood.
 *
 * Step by step:
 *
 *   1. Add user message to history
 *   2. Send history + available tools to LLM via HTTP
 *   3. Did the model request tool calls?
 *      YES -> execute each tool, add results to history, go to step 2
 *      NO  -> return the model answer to the user
 *
 * Safety: maxIterations prevents infinite loops
 * (model could keep calling tools forever without it).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawAgentLoop {

    private final LlmClient llmClient;
    private final DemoTools tools;

    private static final int MAX_ITERATIONS = 10;

    public String chat(String userMessage) {

        // ── Step 1: Initialize conversation history ───────────────────────
        List<Message> history = new ArrayList<>();
        history.add(Message.system(
                "You are a helpful assistant. "
                + "Use available tools when appropriate."));
        history.add(Message.user(userMessage));

        log.info("[LOOP] Starting. User: {}", userMessage);

        // ── Agent loop ────────────────────────────────────────────────────
        for (int i = 1; i <= MAX_ITERATIONS; i++) {
            log.info("[LOOP] Iteration {} — {} messages in history", i, history.size());

            // ── Step 2: Call LLM ─────────────────────────────────────────
            var response = llmClient.chat(history, tools.getDefinitions());
            var message  = response.firstMessage();

            if (message == null) {
                log.error("[LOOP] Empty response from LLM");
                return "Error: empty response from model.";
            }

            // Add assistant message to history
            history.add(Message.assistant(message.content(), message.toolCalls()));

            // Log token usage if available
            if (response.usage() != null) {
                log.info("[LOOP] Tokens: input={} output={} total={}",
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        response.usage().totalTokens());
            }

            // ── Step 3: Tool calls? ───────────────────────────────────────
            if (!response.hasToolCalls()) {
                // No tool calls — final answer
                log.info("[LOOP] Done after {} iteration(s)", i);
                return message.content() != null ? message.content() : "";
            }

            // Execute each tool call and add results to history
            for (ToolCall toolCall : message.toolCalls()) {
                String toolName = toolCall.function().name();
                String toolArgs = toolCall.function().arguments();

                log.info("[LOOP] Tool call: {} args={}", toolName, toolArgs);

                String result = tools.execute(toolName, toolArgs);

                log.info("[LOOP] Tool result: {}", result);

                // Add tool result to history so the model can use it
                history.add(Message.tool(toolCall.id(), result));
            }

            // Go back to step 2 with updated history
        }

        return "Max iterations (" + MAX_ITERATIONS + ") reached.";
    }
}
