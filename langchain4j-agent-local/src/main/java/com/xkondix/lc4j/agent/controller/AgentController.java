package com.xkondix.lc4j.agent.controller;

import com.xkondix.common.dto.ChatRequest;
import com.xkondix.common.dto.ChatResponse;
import com.xkondix.lc4j.agent.service.AiServicesAgentService;
import com.xkondix.lc4j.agent.service.RawAgentLoopService;
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
@Tag(name = "LangChain4j Agent", description = "Agent loop comparison endpoints")
public class AgentController {

    private final RawAgentLoopService rawAgentLoop;
    private final AiServicesAgentService aiServicesAgent;

    @Value("${langchain4j.ollama.chat-model.model-name:ollama/unknown}")
    private String modelName;

    @PostMapping("/raw")
    @Operation(summary = "Raw agent loop (no framework abstractions)")
    public ResponseEntity<ChatResponse> rawLoop(
            @Valid @RequestBody ChatRequest request) {
        String content = rawAgentLoop.chat(request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(modelName)
                .timestamp(Instant.now())
                .build());
    }

    @PostMapping("/aiservices")
    @Operation(summary = "AiServices declarative approach (loop hidden)")
    public ResponseEntity<ChatResponse> aiServices(
            @Valid @RequestBody ChatRequest request) {
        String userId  = request.userId() != null ? request.userId() : "default";
        String content = aiServicesAgent.chat(userId, request.message());
        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(modelName)
                .timestamp(Instant.now())
                .build());
    }
}
