package com.xkondix.springai.agent.service;

import com.xkondix.springai.agent.advisor.ApprovalAdvisor;
import com.xkondix.springai.agent.tools.DemoFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class SpringAiAgentService {

    private final ChatClient chatClient;
    private final DemoFunctions demoFunctions;
    private final MessageWindowChatMemory chatMemory;

    public String chat(String conversationId, String message) {
        log.info("Spring AI chat: conversationId={}", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory)
                        .conversationId(conversationId)
                        .build())
                .tools(demoFunctions)
                .call()
                .content();
    }

    public String chatWithApproval(String conversationId, String message) {
        log.info("Spring AI chatWithApproval: conversationId={}", conversationId);

        return chatClient.prompt()
                .user(message)
                .advisors(
                        new ApprovalAdvisor(),
                        new SimpleLoggerAdvisor(),
                        MessageChatMemoryAdvisor.builder(chatMemory)
                                .conversationId(conversationId)
                                .build())
                .tools(demoFunctions)
                .call()
                .content();
    }
}