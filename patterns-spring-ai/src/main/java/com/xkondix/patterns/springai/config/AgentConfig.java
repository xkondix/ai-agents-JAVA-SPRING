package com.xkondix.patterns.springai.config;

import com.xkondix.patterns.springai.tools.MilanTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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
 */
@Configuration
public class AgentConfig {

    @Bean
    ChatClient plainAgent(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("You are a concise football analyst. Answer briefly.")
                .build();
    }

    @Bean
    ChatClient milanAgent(ChatModel chatModel, MilanTools milanTools) {
        return ChatClient.builder(chatModel)
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
