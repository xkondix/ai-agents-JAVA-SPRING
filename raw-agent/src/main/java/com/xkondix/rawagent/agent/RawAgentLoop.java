package com.xkondix.rawagent.agent;

import com.xkondix.rawagent.model.Message;
import com.xkondix.rawagent.model.ToolCall;
import com.xkondix.rawagent.tools.DemoTools;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
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
 *
 * SPANS BY HAND — the tracing counterpart of the manual metrics in
 * LlmClient. Spring AI gives you "tool_call xyz" spans for free; here we
 * create them ourselves with the low-level Tracer API, so a raw-agent
 * trace in Tempo looks structurally identical to a Spring AI one:
 *   http post /api/v1/agent/chat
 *   ├── chat <model>          (created in LlmClient)
 *   ├── tool_call <name>      (created here)
 *   └── chat <model>
 * Tracer.nextSpan() automatically parents the new span to the current
 * one (the HTTP server span), and withSpan(...) scopes it on this thread
 * so the LlmClient span nests correctly inside the loop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawAgentLoop {

    private final LlmClient llmClient;
    private final DemoTools tools;
    private final Tracer tracer;

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
                String result = executeToolWithSpan(toolCall, i);
                history.add(Message.tool(toolCall.id(), result));
            }
        }

        return "Max iterations (" + MAX_ITERATIONS + ") reached.";
    }

    /**
     * Executes a single tool wrapped in a "tool_call <name>" span —
     * the manual equivalent of what Spring AI emits automatically.
     * Tool errors are returned as text into the history (never thrown),
     * so the span is marked failed only on unexpected exceptions.
     */
    private String executeToolWithSpan(ToolCall toolCall, int iteration) {
        String toolName = toolCall.function().name();
        String toolArgs = toolCall.function().arguments();

        Span span = tracer.nextSpan().name("tool_call " + toolName);
        span.tag("gen_ai.tool.name", toolName);
        span.tag("agent.loop.iteration", String.valueOf(iteration));
        span.tag("framework", "raw");

        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            log.info("[LOOP] Tool call: {} args={}", toolName, toolArgs);
            String result = tools.execute(toolName, toolArgs);
            log.info("[LOOP] Tool result: {}", result);
            return result;
        } catch (RuntimeException e) {
            span.error(e);
            throw e;
        } finally {
            span.end();
        }
    }
}
