package com.xkondix.lc4j.agent.service;

import com.xkondix.lc4j.agent.tools.DemoTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AiServicesAgentService {

    public interface Assistant {
        @SystemMessage("You are a helpful assistant. Use tools when appropriate.")
        String chat(@MemoryId String userId, @UserMessage String message);
    }

    private final Assistant assistant;

    public AiServicesAgentService(ChatModel model, DemoTools demoTools) {
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .tools(demoTools)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    public String chat(String userId, String message) {
        log.info("AiServices chat: userId={}", userId);
        return assistant.chat(userId, message);
    }
}