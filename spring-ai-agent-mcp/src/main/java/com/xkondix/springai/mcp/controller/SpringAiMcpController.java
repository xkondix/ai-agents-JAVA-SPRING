package com.xkondix.springai.mcp.controller;

import com.xkondix.common.dto.ChatRequest;
import com.xkondix.common.dto.ChatResponse;
import com.xkondix.springai.mcp.service.SpringAiOrchestratorService;
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
@Tag(name = "Spring AI MCP", description = "Orchestrator using Spring AI MCP client")
public class SpringAiMcpController {

    private final SpringAiOrchestratorService orchestrator;

    @PostMapping("/chat")
    @Operation(summary = "Chat with Spring AI orchestrator (Java + Python MCP tools)")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request) {
        String content = orchestrator.orchestrate(request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model("ollama/llama3.1")
                .timestamp(Instant.now())
                .build());
    }
}
