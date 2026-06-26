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
 *   1. Add user message to history
 *   2. Send history + available tools to LLM via HTTP
 *   3. Did the model request tool calls?
 *      YES -> execute each tool, add results to history, go to step 2
 *      NO  -> return the model answer to the user
 *
 * Safety: maxIterations prevents infinite loops.
 * Graceful degradation: if LLM is unavailable, returns a readable error
 * instead of propagating a stack trace to the user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawAgentLoop {

    private final LlmClient llmClient;
    private final DemoTools tools;

    private static final int MAX_ITERATIONS = 10;

    public String chat(String userMessage) {
        try {
            return doChat(userMessage);
        } catch (RuntimeException e) {
            // Graceful degradation — LLM unavailable or API error
            log.error("[LOOP] LLM call failed: {}", e.getMessage());
            if (e.getMessage() != null && e.getMessage().contains("Connection refused")) {
                return "LLM is unavailable. Please check that Ollama is running on port 11434. " +
                       "Run: ollama serve";
            }
            if (e.getMessage() != null && e.getMessage().contains("LLM API error")) {
                return "LLM returned an error: " + e.getMessage();
            }
            return "Agent error: " + e.getMessage();
        }
    }

    private String doChat(String userMessage) {
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

            history.add(Message.assistant(message.content(), message.toolCalls()));

            if (response.usage() != null) {
                log.info("[LOOP] Tokens: input={} output={} total={}",
                        response.usage().promptTokens(),
                        response.usage().completionTokens(),
                        response.usage().totalTokens());
            }

            // ── Step 3: Tool calls? ───────────────────────────────────────
            if (!response.hasToolCalls()) {
                log.info("[LOOP] Done after {} iteration(s)", i);
                return message.content() != null ? message.content() : "";
            }

            for (ToolCall toolCall : message.toolCalls()) {
                String toolName = toolCall.function().name();
                String toolArgs = toolCall.function().arguments();
                log.info("[LOOP] Tool call: {} args={}", toolName, toolArgs);
                String result = tools.execute(toolName, toolArgs);
                log.info("[LOOP] Tool result: {}", result);
                history.add(Message.tool(toolCall.id(), result));
            }
        }

        return "Max iterations (" + MAX_ITERATIONS + ") reached.";
    }
}
