package com.xkondix.patterns.springai.config;

import com.xkondix.patterns.springai.tools.MilanTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agents as beans. Two flavours, injected by @Qualifier into pattern classes:
 *
 *  - plainAgent — no tools; used for pure-LLM steps (chain links, routing
 *    classification, scoring, evaluation). Cheap and deterministic-ish.
 *  - milanAgent — MilanTools attached; used wherever a step needs data
 *    (specialists in routing, workers in orchestrator).
 *
 * KEY COMPARISON POINT vs LangChain4j: this is ALL the "framework" you get.
 * Spring AI ships no sequence/loop/conditional builders — the patterns
 * themselves are plain Java in the pattern classes.
 *
 * ── WHY THE AUTO-CONFIGURED ChatClient.Builder, NOT ChatClient.builder(chatModel)
 *
 * The static factory looks like the obvious choice and is what this class
 * used to call. It is also the reason Spring AI tool calls never appeared in
 * Tempo: ChatClient.builder(chatModel) creates
 * DefaultChatClientBuilder(chatModel, ObservationRegistry.NOOP, null, null)
 * (spring-ai-client-chat, DefaultChatClientBuilder), so every observation
 * owned by the ChatClient layer — spring.ai.chat.client, advisors and the
 * spring.ai.tool observation around each tool execution — is a no-op. The
 * ChatModel span survived only because the ChatModel bean is auto-configured
 * with the real registry.
 *
 * The symptom on stage: LangChain4j and raw-agent traces show
 * chat → tool_call → chat, the Spring AI trace shows chat → chat with the tool
 * visible only in a log line, and the "Tool executions (Spring AI)" panel
 * stays at No data. It looked like a Spring AI 2.0 gap; it was this call.
 *
 * The auto-configured ChatClient.Builder (ChatClientAutoConfiguration) carries
 * the ObservationRegistry, the observation conventions and every
 * ChatClientCustomizer. It is a PROTOTYPE bean, so each injection point below
 * receives its own fresh builder — two beans, two builders, no shared state.
 * Reference: docs.spring.io/spring-ai/reference/api/chatclient.html,
 * "Working with Multiple Chat Models".
 */
@Configuration
public class AgentConfig {

    @Bean
    ChatClient plainAgent(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You are a concise football analyst. Answer briefly.")
                .build();
    }

    @Bean
    ChatClient milanAgent(ChatClient.Builder builder, MilanTools milanTools) {
        return builder
                .defaultSystem("""
                        You are an AC Milan data analyst.
                        Use the available tools to fetch squads, transfers,
                        player stats and (only when explicitly asked) secret rumors.
                        Never invent data — if a tool returns nothing, say so.
                        """)
                .defaultTools(milanTools)
                .build();
    }
}
