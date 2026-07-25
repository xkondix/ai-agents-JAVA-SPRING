package com.xkondix.lc4j.agent.service;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * RAW AGENT LOOP — no framework abstractions, just the LLM API.
 * Shows what every framework does under the hood.
 *
 * The loop:
 *   1. Build messages list (system + user + history)
 *   2. Call LLM with available tools
 *   3. Tool calls in response? Execute -> add result -> go to 2
 *   4. No tool calls? Return final answer
 *
 * Observability: the model call is covered by the shared
 * GenAiMetricsChatModelListener (chat span + GenAI metrics), but tool
 * execution here bypasses AiServices entirely — it is our own code, so the
 * "tool_call <name>" spans are created by hand, exactly like in the
 * framework-free raw-agent module. Same pattern, same tags
 * (gen_ai.tool.name, agent.loop.iteration), span.end() in finally.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawAgentLoopService {

    private final ChatModel chatModel;
    private final RawToolExecutor toolExecutor;
    private final Tracer tracer;

    public String chat(String userMessage) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new SystemMessage(
                "You are a helpful assistant. " +
                "Use available tools when needed."));
        messages.add(new UserMessage(userMessage));

        int iteration     = 0;
        int maxIterations = 10;

        while (iteration++ < maxIterations) {
            log.info("[RAW LOOP] Iteration {} — {} messages", iteration, messages.size());

            ChatResponse response = chatModel.chat(
                    ChatRequest.builder()
                            .messages(messages)
                            .parameters(ChatRequestParameters.builder()
                                    .toolSpecifications(toolExecutor.getToolSpecs())
                                    .build())
                            .build());

            AiMessage aiMessage = response.aiMessage();
            messages.add(aiMessage);

            if (!aiMessage.hasToolExecutionRequests()) {
                log.info("[RAW LOOP] Done after {} iterations", iteration);
                return aiMessage.text();
            }

            for (var toolRequest : aiMessage.toolExecutionRequests()) {
                String result = executeToolWithSpan(
                        toolRequest.name(), toolRequest.arguments(), iteration);
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }
        }

        return "Max iterations reached.";
    }

    /**
     * Executes one tool inside a "tool_call &lt;name&gt;" span.
     * Tool failures are returned to the model as text (never thrown), so the
     * loop keeps going; the span is still marked with the error.
     */
    private String executeToolWithSpan(String toolName, String arguments, int iteration) {
        log.info("[RAW LOOP] Tool: {} args={}", toolName, arguments);

        Span span = tracer.nextSpan().name("tool_call " + toolName);
        span.tag("gen_ai.tool.name", toolName);
        span.tag("agent.loop.iteration", String.valueOf(iteration));
        span.tag("framework", "langchain4j");

        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            String result = toolExecutor.execute(toolName, arguments);
            log.info("[RAW LOOP] Result: {}", result);
            return result;
        } catch (Exception e) {
            span.error(e);
            log.error("[RAW LOOP] Tool failed: {}", toolName, e);
            return "TOOL ERROR: " + e.getMessage();
        } finally {
            span.end();
        }
    }
}
