package com.xkondix.springai.agent.service;

import com.xkondix.springai.agent.advisor.ApprovalAdvisor;
import com.xkondix.springai.agent.tools.DemoFunctions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.stereotype.Service;

/**
 * Spring AI agent over local @Tool methods.
 *
 * SPRING AI 2.0 — CONVERSATION ID MOVED FROM THE ADVISOR TO THE REQUEST.
 * MessageChatMemoryAdvisor.Builder used to carry a conversationId; in 2.0 the
 * builder exposes only order() and scheduler(), and the advisor reads the id
 * from the request context instead:
 *
 *     String conversationId = getConversationId(chatClientRequest.context());
 *
 * So the id is now passed per call through the advisor params. This is a better
 * fit anyway: the advisor instance is stateless and safe to share, whereas the
 * old builder baked one conversation into the advisor and forced a new instance
 * per request — which is exactly what this class was doing.
 *
 * TOOL EXECUTION IS NO LONGER THE MODEL'S JOB. In 2.0 the built-in tool loop was
 * removed from every ChatModel and lifted into the advisor chain: ChatClient
 * auto-registers a ToolCallingAdvisor whenever tools are present. Nothing to do
 * here — .tools(...) still works — but do NOT add a ToolCallingAdvisor by hand,
 * or it ends up in the chain twice.
 */
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
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
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
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .tools(demoFunctions)
                .call()
                .content();
    }
}
