package com.xkondix.rawagent.controller;

import com.xkondix.common.dto.ChatRequest;
import com.xkondix.common.dto.ChatResponse;
import com.xkondix.rawagent.agent.RawAgentLoop;
import com.xkondix.rawagent.config.RawAgentProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/agent")
@RequiredArgsConstructor
@Tag(name = "Raw Agent", description = "Pure agent loop — no LangChain4j, no Spring AI")
public class RawAgentController {

    private final RawAgentLoop agentLoop;
    private final RawAgentProperties props;

    @PostMapping("/chat")
    @Operation(summary = "Chat with raw agent loop (pure HTTP + Jackson, no AI framework)")
    public ResponseEntity<ChatResponse> chat(
            @Valid @RequestBody ChatRequest request) {

        String content = agentLoop.chat(request.message());

        String model = "ollama".equals(props.getProvider())
                ? props.getOllama().getModel()
                : props.getOpenai().getModel();

        return ResponseEntity.ok(ChatResponse.builder()
                .content(content)
                .model(model)
                .timestamp(Instant.now())
                .build());
    }
}
