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

import java.util.List;
import java.util.UUID;

/**
 * Orchestrator Agent — uses tools from MCP servers.
 *
 * The agent does not know (or care) that tools run in a different process:
 * get_game_stats, save_note, search_notes, delete_note and get_weather all
 * live in mcp-server (port 8081, Streamable HTTP). It sees a flat list of
 * tools and picks one. This is the core MCP orchestrator demo for
 * Presentation 2.
 *
 * ── ONE SERVER, ONE CLIENT ─────────────────────────────────────────────────
 *
 * mcp-server is the only MCP server this agent connects to. A second server
 * would mean a second McpClient in the list below, and McpToolProvider would
 * merge its tools into the same flat list. claude-mcp-server is not one of
 * them: it speaks STDIO and is launched by Claude Desktop.
 *
 * The system prompt lists only tools that exist. An unavailable tool in a
 * prompt is worse than no tool: the model offers it, tries to use it, and only
 * then discovers it does not exist. The rule "say so instead of pretending"
 * does not help, because from the model's point of view the prompt is the
 * evidence.
 *
 * Observability: McpToolProvider is wrapped in TracingToolProvider (module
 * `common`, shared with patterns-langchain4j), so every tool execution shows
 * up as an "execute_tool <name>" span in Tempo — the LC4j trace has the same
 * shape as Spring AI and raw-agent traces
 * (http post → chat → execute_tool → chat).
 *
 * ── TRACE PROPAGATION IS OFF BY DEFAULT, ON PURPOSE ────────────────────────
 *
 * By default this module does not inject the W3C trace context into MCP
 * calls, so a tool call produces TWO separate traces: one for the agent, one
 * for mcp-server. `spring-ai-agent-mcp` does propagate and shows `Services: 2`
 * for the same operation. Same protocol, same transport, same tool — the
 * difference is a client implementation decision, and having both side by
 * side is the point. lc4j.mcp.trace-propagation.enabled=true turns it on with
 * one header supplier (see McpClientConfig), so the switch itself is a demo.
 * See OBSERVABILITY.md.
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
                Your tools come from MCP servers running in other processes:
                  get_game_stats, get_weather, search_notes, save_note, delete_note
                Use the most appropriate available tool for each task. If no tool
                fits, say so instead of pretending to use one.
                Always explain which tool you chose and why.
                save_note and delete_note wait until a human approves or rejects them.
                Report what the tool returned. Never ask the user to approve again.
                """)
        String chat(@MemoryId String conversationId, @UserMessage String message);
    }

    private final OrchestratorAssistant assistant;

    public OrchestratorService(
            ChatModel model,
            @Qualifier("javaMcpClient") McpClient javaMcpClient,
            ObjectProvider<Tracer> tracerProvider) {

        ToolProvider toolProvider = new TracingToolProvider(
                McpToolProvider.builder().mcpClients(List.of(javaMcpClient)).build(),
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
