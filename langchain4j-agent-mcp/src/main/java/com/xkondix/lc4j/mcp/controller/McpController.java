package com.xkondix.lc4j.mcp.controller;

import com.xkondix.common.dto.ChatRequest;
import com.xkondix.common.dto.ChatResponse;
import com.xkondix.lc4j.mcp.service.OrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
@Tag(name = "LangChain4j MCP", description = "Orchestrator using MCP servers")
public class McpController {

    private final OrchestratorService orchestrator;

    @PostMapping("/chat")
    @Operation(summary = "Chat with the orchestrator (tools from mcp-server over MCP)")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request) {
        // Memory key: conversationId wins, userId is the fallback (the chat-ui
        // sends a per-tab userId), nothing at all means a one-off conversation.
        String conversationId = request.conversationId() != null && !request.conversationId().isBlank()
                ? request.conversationId()
                : request.userId();
        String content = orchestrator.orchestrate(conversationId, request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model("openai/gpt-4o-mini")
                .timestamp(Instant.now())
                .build());
    }
}
