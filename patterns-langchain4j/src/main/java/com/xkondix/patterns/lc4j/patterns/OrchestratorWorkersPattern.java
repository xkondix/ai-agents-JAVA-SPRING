package com.xkondix.patterns.lc4j.patterns;

import com.xkondix.patterns.lc4j.tools.MilanTools;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PATTERN 5 — Orchestrator-workers.
 *
 * A central LLM PLANS AT RUNTIME: it decomposes the task into subtasks that
 * could not be hardcoded, delegates them to workers, and synthesizes the
 * results. Difference vs Parallelization: there YOU wrote the branches;
 * here the orchestrator invents them per request.
 *
 * LangChain4j implementation: orchestrator-as-agent — one AiServices agent
 * whose "workers" are the Milan tools; the model plans the tool sequence
 * itself. The Agentic DSL alternative is supervisorBuilder(), where workers
 * are full sub-agents supervised by a coordinator — same idea, heavier
 * machinery; we keep the tool-based variant for the demo because the
 * planning behavior is identical and easier to read in a single class.
 *
 * Trace signature: irregular — "chat", then a tool sequence you did NOT
 * know in advance, then "chat"; every run may produce a different shape.
 */
@Slf4j
@Service
public class OrchestratorWorkersPattern {

    interface Orchestrator {
        @SystemMessage("""
                You are an orchestrator for AC Milan analytics.
                Break the user's task into subtasks and solve them with the
                available tools (squad, player stats, transfers, rumors).
                Plan first, then call the tools you need — in any order,
                as many times as necessary. Finally synthesize one coherent
                answer. Never invent data: every fact must come from a tool.
                """)
        String execute(@UserMessage String task);
    }

    private final Orchestrator orchestrator;

    public OrchestratorWorkersPattern(ChatModel chatModel, MilanTools milanTools) {
        this.orchestrator = AiServices.builder(Orchestrator.class)
                .chatModel(chatModel)
                .tools(milanTools)
                .build();
    }

    public String run(String task) {
        log.info("[ORCHESTRATOR] task: {}", task);
        return orchestrator.execute(task);
    }
}
