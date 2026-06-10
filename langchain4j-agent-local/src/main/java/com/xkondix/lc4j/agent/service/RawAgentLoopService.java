package com.xkondix.lc4j.agent.service;

import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
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
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RawAgentLoopService {

    private final ChatModel chatModel;
    private final RawToolExecutor toolExecutor;

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
                log.info("[RAW LOOP] Tool: {} args={}", toolRequest.name(), toolRequest.arguments());

                String result;
                try {
                    result = toolExecutor.execute(toolRequest.name(), toolRequest.arguments());
                } catch (Exception e) {
                    result = "TOOL ERROR: " + e.getMessage();
                    log.error("[RAW LOOP] Tool failed: {}", toolRequest.name(), e);
                }

                log.info("[RAW LOOP] Result: {}", result);
                messages.add(ToolExecutionResultMessage.from(toolRequest, result));
            }
        }

        return "Max iterations reached.";
    }
}
