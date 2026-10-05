package com.xkondix.springai.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI configuration.
 *
 * ChatClient is built via builder (not AiServices interface like LangChain4j).
 * Memory is configured as a Repository — Spring Data style.
 * Advisors are explicit interceptors added to the pipeline.
 *
 * THE BUILDER IS INJECTED, NOT CREATED. ChatClient.builder(chatModel) hands
 * the ChatClient an ObservationRegistry.NOOP, which silently drops every
 * ChatClient-level observation — including the spring.ai.tool span around
 * each tool execution. That is why Spring AI traces showed chat → chat with
 * getWeather present only as a log line, while LangChain4j and raw-agent
 * showed chat → execute_tool → chat. The auto-configured ChatClient.Builder
 * (prototype bean) carries the real registry, and Spring AI then emits its
 * own "execute_tool <name>" span. Full note in
 * patterns-spring-ai/config/AgentConfig.
 *
 * THE DEFAULT MEMORY ADVISOR BELOW IS A DELIBERATE DUPLICATE. Every method in
 * SpringAiAgentService adds its own MessageChatMemoryAdvisor per request, so
 * each call runs with two memory advisors on the same conversation id — the
 * setup the documentation advises against, kept to show its effect (two
 * message_chat_memory spans; the question twice in the memory-in-loop
 * prompt). Delete defaultAdvisors(...) to get the documented behaviour.
 */
@Configuration
public class SpringAiConfig {

    @Bean
    public InMemoryChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    public MessageWindowChatMemory chatMemory(
            InMemoryChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            MessageWindowChatMemory chatMemory) {

        return builder
                .defaultSystem("You are a helpful assistant. " +
                        "Use available tools when needed.")
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }
}
