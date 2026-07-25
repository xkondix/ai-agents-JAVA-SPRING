package com.xkondix.patterns.lc4j.patterns;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
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
 * Note this is a MUCH cheaper interpretation than the Spring AI module,
 * where each worker is its own LLM call (planner → workers → synthesis).
 * Same pattern name, two readings — compare the traces: ~2 chat spans here
 * versus a dozen there. Worth showing side by side.
 *
 * Tools come from an instrumented ToolProvider (see ToolsConfig), so every
 * execution shows up as a "tool_call <name>" span.
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

    public OrchestratorWorkersPattern(ChatModel chatModel, ToolProvider milanToolProvider) {
        this.orchestrator = AiServices.builder(Orchestrator.class)
                .chatModel(chatModel)
                .toolProvider(milanToolProvider)
                .build();
    }

    public String run(String task) {
        log.info("[ORCHESTRATOR] task: {}", task);
        return orchestrator.execute(task);
    }
}
