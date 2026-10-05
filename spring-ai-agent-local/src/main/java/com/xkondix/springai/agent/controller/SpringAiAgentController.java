package com.xkondix.springai.agent.controller;

import com.xkondix.common.dto.ChatRequest;
import com.xkondix.common.dto.ChatResponse;
import com.xkondix.springai.agent.service.SpringAiAgentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

/**
 * Three endpoints, one variable: the advisor chain.
 *
 *   /chat               memory outside the loop (the default), tool loop only
 *   /chat/advisors      + a hand-written advisor and a built-in logger
 *   /chat/memory-in-loop  memory moved inside the loop by raising its order
 *
 * Same model, same tools, same question. Whatever differs in the logs, the
 * trace or the stored conversation comes from the chain and nothing else.
 *
 * /chat/advisors used to be /chat/approval and was commented out, because the
 * advisor behind it promised a human-approval gate that was never written. The
 * gate is real but lives elsewhere — mcp-server and both patterns modules,
 * where there are destructive tools to guard. Here there are three read-only
 * demo functions, so the endpoint shows the PATTERN instead of pretending to
 * be a feature.
 */
@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Spring AI Agent", description = "ChatClient + Advisors demo")
public class SpringAiAgentController {

    private final SpringAiAgentService agentService;

    @Value("${spring.ai.ollama.chat.options.model:ollama/unknown}")
    private String modelName;

    @PostMapping("/chat")
    @Operation(summary = "Minimal chain: memory outside the loop + the "
            + "auto-registered ToolCallingAdvisor")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request) {
        return respond(agentService.chat(conversationId(request), request.message()));
    }

    @PostMapping("/chat/advisors")
    @Operation(summary = "Longer chain: InspectionAdvisor (inside the loop) + "
            + "SimpleLoggerAdvisor + memory")
    public ResponseEntity<ChatResponse> chatWithAdvisors(
            @Valid @RequestBody ChatRequest request) {
        return respond(agentService.chatWithAdvisors(
                conversationId(request), request.message()));
    }

    @PostMapping("/chat/memory-in-loop")
    @Operation(summary = "Memory inside the loop: the stored conversation keeps "
            + "the whole tool transcript, not just the final answer")
    public ResponseEntity<ChatResponse> chatWithMemoryInLoop(
            @Valid @RequestBody ChatRequest request) {
        return respond(agentService.chatWithMemoryInLoop(
                conversationId(request), request.message()));
    }

    private static String conversationId(ChatRequest request) {
        return request.conversationId() != null ? request.conversationId() : "default";
    }

    private ResponseEntity<ChatResponse> respond(String content) {
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(modelName)
                .timestamp(Instant.now())
                .build());
    }
}
