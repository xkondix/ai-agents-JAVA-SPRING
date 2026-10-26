package com.xkondix.springai.mcp.controller;

import com.xkondix.common.dto.ChatRequest;
import com.xkondix.common.dto.ChatResponse;
import com.xkondix.springai.mcp.service.SpringAiOrchestratorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/mcp")
@RequiredArgsConstructor
@Tag(name = "Spring AI MCP", description = "Orchestrator using Spring AI MCP client")
public class SpringAiMcpController {

    private final SpringAiOrchestratorService orchestrator;

    // OpenAI key first (profile openrouter), Ollama key as the fallback (profile local)
    @Value("${spring.ai.openai.chat.model:${spring.ai.ollama.chat.model:unknown}}")
    private String modelName;

    @PostMapping("/chat")
    @Operation(summary = "Chat with Spring AI orchestrator (Java + Python MCP tools)")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request) {
        String content = orchestrator.orchestrate(request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(modelName)
                .timestamp(Instant.now())
                .build());
    }
}
