package com.xkondix.mcpserver.tools;

import com.xkondix.mcpserver.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knowledge base tools exposed via MCP.
 * In-memory storage — data is lost on restart.
 * For production: replace with Redis or a database.
 *
 * SPRING AI 2.0 — @McpTool, NOT @Tool. See GameStatsTools for why the two
 * annotations now mean different things.
 *
 * Demonstrates the Human-in-the-loop Approval Flow:
 *   - save_note   : requires approval before writing
 *   - delete_note : requires approval before deleting
 *   - search_notes: safe, no approval
 *
 * The guarded work is passed to approvalService.gate(...) as a lambda: the
 * tool has no if/else boilerplate and CANNOT execute the action without a
 * decision — the shape of the API enforces the rule.
 *
 * THE APPROVAL FLOW IS WHY THIS SERVER SPEAKS HTTP.
 * gate(...) blocks the calling thread until a human decides, and that decision
 * arrives over a SECOND channel: the Approval REST API on :8081, driven from
 * the UI on :3000. Over STDIO there is no second channel — the single stdin/
 * stdout pipe is already carrying JSON-RPC, and blocking its thread deadlocks
 * the stream outright. That is the whole reason claude-mcp-server has no
 * approval flow, and the contrast between the two transports is the lesson.
 *
 * Blocking here is safe because spring.threads.virtual.enabled=true: each
 * request runs on a virtual thread, so pending approvals park cheaply instead
 * of exhausting a platform-thread pool.
 *
 * The destructiveHint below is not decoration — MCP clients use those hints to
 * decide which calls to surface for confirmation, which is exactly the same
 * intent the approval gate enforces server-side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseTools {

    private final ApprovalService approvalService;

    // In-memory store — mock for demo purposes
    private final Map<String, String> notes = new ConcurrentHashMap<>();

    @McpTool(name = "save_note", description = """
            REQUIRES HUMAN APPROVAL.
            Save a note to the knowledge base.
            The operation pauses until approved at http://localhost:3000/approvals
            Returns the generated note ID, or REJECTED if cancelled.
            """)
    public String save_note(
            @McpToolParam(description = "Note title", required = true)
            String title,
            @McpToolParam(description = "Note content", required = true)
            String content) {
        log.info("[MCP] save_note PENDING APPROVAL title={}", title);

        return approvalService.gate(
                "SAVE_NOTE", "save_note",
                "Save note: " + title,
                "TITLE: " + title + "\nCONTENT:\n" + content,
                () -> {
                    String id = "note-" + Instant.now().toEpochMilli();
                    notes.put(id, "[" + title + "] " + content);
                    log.info("[MCP] save_note id={} title={}", id, title);
                    return "Saved: " + id;
                },
                "REJECTED: Note was not saved.");
    }

    @McpTool(name = "delete_note", description = """
            REQUIRES HUMAN APPROVAL.
            Delete a note from the knowledge base by its ID.
            The operation pauses until approved at http://localhost:3000/approvals
            Returns confirmation, or REJECTED if cancelled.
            """,
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public String delete_note(
            @McpToolParam(description = "Note ID to delete", required = true)
            String id) {
        log.info("[MCP] delete_note PENDING APPROVAL id={}", id);

        if (!notes.containsKey(id)) {
            return "ERROR: No note found with id: " + id;
        }

        return approvalService.gate(
                "DELETE_NOTE", "delete_note",
                "Delete note: " + id,
                "ID: " + id + "\nCONTENT: " + notes.get(id),
                () -> {
                    notes.remove(id);
                    log.info("[MCP] delete_note id={} deleted", id);
                    return "Deleted: " + id;
                },
                "REJECTED: Note was not deleted.");
    }

    @McpTool(name = "search_notes", description = """
            Search notes in the knowledge base by keyword (case-insensitive).
            Safe operation — no approval required.
            Returns matching notes with their IDs.
            """,
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String search_notes(
            @McpToolParam(description = "Search keyword", required = true)
            String query) {
        log.info("[MCP] search_notes query={}", query);
        String result = notes.entrySet().stream()
                .filter(e -> e.getValue().toLowerCase().contains(query.toLowerCase()))
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce("", (a, b) -> a + "\n" + b);
        return result.isBlank() ? "No notes found for: " + query : result.trim();
    }
}
