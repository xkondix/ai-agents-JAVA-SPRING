package com.xkondix.lc4j.mcp.service;

import com.xkondix.common.observability.TracingToolProvider;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Orchestrator Agent — uses tools from MCP servers.
 *
 * The agent does not know (or care) that tools run in different processes:
 *   - get_game_stats, save_note, get_weather  → mcp-server      (port 8081)
 *   - read_file, write_file, search_in_files  → a second MCP server (optional —
 *     present only with lc4j.mcp.code-server.enabled=true)
 *
 * It just sees a flat list of tools and picks the right one.
 * This is the core MCP orchestrator demo for Presentation 2.
 *
 * Observability: McpToolProvider is wrapped in TracingToolProvider (module
 * `common`, shared with patterns-langchain4j), so every tool execution shows
 * up as a "tool_call <n>" span in Tempo — the LC4j trace has the same
 * shape as Spring AI and raw-agent traces
 * (http post → chat → tool_call → chat).
 *
 * ── MEMORY IS PER CONVERSATION, NOT PER PROCESS ─────────────────────────────
 *
 * The first version used chatMemory(MessageWindowChatMemory.withMaxMessages(30))
 * — ONE memory shared by every caller of this process. Harmless in a single
 * IDE session, a trap on stage: a rejected approval from the first demo stays
 * in the history, and in the next scenario the model "remembers" that
 * save_note was refused and may not try again. Restarting the module between
 * scenarios was the workaround.
 *
 * Now the AiService takes a @MemoryId and the memory comes from a provider,
 * one window per id. The id is the conversationId (or userId) from the
 * request, so a client that wants continuity sends the same id; a client that
 * sends none gets a fresh id — and a fresh memory — per request. The chat-ui
 * sends a per-tab userId, which is exactly the granularity a demo wants:
 * a new tab is a clean slate.
 *
 * Same idea in Spring AI terms: MessageChatMemoryAdvisor keyed by
 * conversationId (see spring-ai-agent-local). LangChain4j spells it
 * chatMemoryProvider + @MemoryId.
 */
@Slf4j
@Service
public class OrchestratorService {

    private static final int MEMORY_WINDOW = 30;

    private interface OrchestratorAssistant {
        @SystemMessage("""
                You are an orchestrator agent.
                You have access to tools provided by MCP servers, for example:
                - mcp-server tools:      get_game_stats, save_note, search_notes,
                                         delete_note, get_weather
                - code-mcp-server tools (optional): read_file, list_files,
                                         get_project_structure, search_in_files,
                                         write_file, create_file, move_file, delete_file
                Use the most appropriate available tool for each task. If a tool
                is not available, say so instead of pretending to use it.
                Always explain which tool you chose and why.
                For operations that modify data, always wait for human approval.
                """)
        String chat(@MemoryId String conversationId, @UserMessage String message);
    }

    private final OrchestratorAssistant assistant;

    public OrchestratorService(
            ChatModel model,
            @Qualifier("javaMcpClient") McpClient javaMcpClient,
            @Qualifier("codeMcpClient") Optional<McpClient> codeMcpClient,
            ObjectProvider<Tracer> tracerProvider) {

        List<McpClient> clients = new ArrayList<>();
        clients.add(javaMcpClient);
        codeMcpClient.ifPresentOrElse(
                client -> {
                    clients.add(client);
                    log.info("code-mcp-server client ENABLED — file tools available");
                },
                () -> log.info("code-mcp-server client disabled "
                        + "(lc4j.mcp.code-server.enabled=false) — file tools unavailable"));

        ToolProvider toolProvider = new TracingToolProvider(
                McpToolProvider.builder().mcpClients(clients).build(),
                tracerProvider.getIfAvailable());

        this.assistant = AiServices.builder(OrchestratorAssistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(MEMORY_WINDOW))
                .build();
    }

    /**
     * @param conversationId key of the memory window; null or blank means
     *                       "no continuity wanted" and gets a one-off id
     */
    public String orchestrate(String conversationId, String message) {
        String id = (conversationId == null || conversationId.isBlank())
                ? "oneoff-" + UUID.randomUUID()
                : conversationId;
        log.info("Orchestrator received (conversation={}): {}", id, message);
        return assistant.chat(id, message);
    }

    /** Kept for callers that do not care about continuity. */
    public String orchestrate(String message) {
        return orchestrate(null, message);
    }
}
