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

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Spring AI Agent", description = "ChatClient + Advisors demo")
public class SpringAiAgentController {

    private final SpringAiAgentService agentService;

    @Value("${spring.ai.ollama.chat.options.model:ollama/unknown}")
    private String modelName;

    @PostMapping("/chat")
    @Operation(summary = "Chat with Spring AI agent (ChatClient + @Tool)")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request) {
        String convId  = request.conversationId() != null
                ? request.conversationId() : "default";
        String content = agentService.chat(convId, request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(modelName)
                .timestamp(Instant.now())
                .build());
    }

    @PostMapping("/chat/approval")
    @Operation(summary = "Chat with ApprovalAdvisor (sensitive tool calls)")
    public ResponseEntity<ChatResponse> chatWithApproval(
            @Valid @RequestBody ChatRequest request) {
        String convId  = request.conversationId() != null
                ? request.conversationId() : "default";
        String content = agentService.chatWithApproval(convId, request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(modelName)
                .timestamp(Instant.now())
                .build());
    }
}
