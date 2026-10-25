package com.xkondix.patterns.lc4j.patterns;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.tool.ToolProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * PATTERN 5 — Orchestrator-workers.
 *
 * A central LLM PLANS AT RUNTIME: it decomposes the task into subtasks that
 * could not be hardcoded, delegates each one to a worker LLM, and synthesizes
 * the results. Difference vs Parallelization: there YOU wrote the branches;
 * here the orchestrator invents them per request.
 *
 * LangChain4j implementation: three AiServices and plain Java — plan
 * (structured output, a Plan record), dispatch (one worker call per subtask,
 * tools attached), synthesize (final call). The same three phases and the
 * same prompts as the Spring AI module, so the two traces and timings are
 * comparable.
 *
 * WHY NOT ONE AGENT WITH TOOLS. An earlier version handed the whole task to a
 * single AiServices agent and let the model pick the tools. That is an agent
 * (an LLM using tools in a loop), not orchestrator-workers: there was no
 * visible plan and no worker LLMs, only tool calls.
 *
 * WHY NOT supervisorBuilder(). The Agentic DSL variant lets a supervisor LLM
 * pick the next sub-agent turn by turn, so there is no upfront plan. Here the
 * plan is a typed value that shows up in the log and in the trace.
 *
 * Tools come from an instrumented ToolProvider (see ToolsConfig), so every
 * execution shows up as an "execute_tool <name>" span.
 *
 * Trace signature: a "chat" (plan), then one worker "chat" per subtask with
 * its tool spans, then a final "chat". How many workers run is decided at
 * runtime — that IS the fingerprint.
 */
@Slf4j
@Service
public class OrchestratorWorkersPattern {

    public record Plan(List<String> subtasks) {}

    interface Planner {
        @SystemMessage("You are a concise football analyst. Answer briefly.")
        @UserMessage("Decompose this AC Milan analysis task into 2-4 concrete, "
                + "independent data-gathering subtasks (each answerable with "
                + "squad/transfer/stats/rumor data): {{task}}")
        Plan plan(@V("task") String task);
    }

    interface Worker {
        @SystemMessage("""
                You are an AC Milan data analyst.
                Use the available tools to fetch squads, transfers,
                player stats and (only when explicitly asked) secret rumors.
                Never invent data — if a tool returns nothing, say so.
                """)
        String work(@UserMessage String subtask);
    }

    interface Synthesizer {
        @SystemMessage("You are a concise football analyst. Answer briefly.")
        @UserMessage("Synthesize a final answer to the task: '{{task}}' "
                + "from these worker results:\n{{workerResults}}")
        String synthesize(@V("task") String task, @V("workerResults") String workerResults);
    }

    private final Planner planner;
    private final Worker worker;
    private final Synthesizer synthesizer;

    public OrchestratorWorkersPattern(ChatModel chatModel, ToolProvider milanToolProvider) {
        this.planner = AiServices.builder(Planner.class)
                .chatModel(chatModel)
                .build();                                   // no tools: it only plans
        this.worker = AiServices.builder(Worker.class)
                .chatModel(chatModel)
                .toolProvider(milanToolProvider)            // only the workers get tools
                .build();
        this.synthesizer = AiServices.builder(Synthesizer.class)
                .chatModel(chatModel)
                .build();
    }

    public String run(String task) {
        log.info("[ORCHESTRATOR] task: {}", task);

        // Phase 1 — the orchestrator decomposes the task (typed plan)
        Plan plan = planner.plan(task);
        log.info("[ORCHESTRATOR] plan: {}", plan.subtasks());

        // Phase 2 — workers execute sub-tasks (tools attached)
        String workerResults = plan.subtasks().stream()
                .map(subtask -> {
                    log.info("[ORCHESTRATOR] worker -> {}", subtask);
                    return "- " + subtask + "\n  " + worker.work(subtask);
                })
                .collect(Collectors.joining("\n"));

        // Phase 3 — synthesis
        return synthesizer.synthesize(task, workerResults);
    }
}
