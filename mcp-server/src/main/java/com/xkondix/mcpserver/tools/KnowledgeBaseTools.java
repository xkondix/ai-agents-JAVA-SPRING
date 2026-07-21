package com.xkondix.mcpserver.tools;

import com.xkondix.mcpserver.approval.ApprovalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Knowledge base tools exposed via MCP.
 * In-memory storage — data is lost on restart.
 * For production: replace with Redis or a database.
 *
 * Spring AI @Tool approach — JSON Schema generated automatically.
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
 * This works here because mcp-server runs over HTTP (SYNC_HTTP_SSE), so the
 * Approval REST API on :8081 is reachable and approve/reject can unblock the
 * waiting tool call. (The STDIO-based code-mcp-server cannot do this — see its
 * README.)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseTools {

    private final ApprovalService approvalService;

    // In-memory store — mock for demo purposes
    private final Map<String, String> notes = new ConcurrentHashMap<>();

    @Tool(description = """
            REQUIRES HUMAN APPROVAL.
            Save a note to the knowledge base.
            The operation pauses until approved at http://localhost:3000/approvals
            Returns the generated note ID, or REJECTED if cancelled.
            """)
    public String save_note(
            @ToolParam(description = "Note title") String title,
            @ToolParam(description = "Note content") String content) {
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

    @Tool(description = """
            REQUIRES HUMAN APPROVAL.
            Delete a note from the knowledge base by its ID.
            The operation pauses until approved at http://localhost:3000/approvals
            Returns confirmation, or REJECTED if cancelled.
            """)
    public String delete_note(
            @ToolParam(description = "Note ID to delete") String id) {
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

    @Tool(description = """
            Search notes in the knowledge base by keyword (case-insensitive).
            Safe operation — no approval required.
            Returns matching notes with their IDs.
            """)
    public String search_notes(
            @ToolParam(description = "Search keyword") String query) {
        log.info("[MCP] search_notes query={}", query);
        String result = notes.entrySet().stream()
                .filter(e -> e.getValue().toLowerCase().contains(query.toLowerCase()))
                .map(e -> e.getKey() + ": " + e.getValue())
                .reduce("", (a, b) -> a + "\n" + b);
        return result.isBlank() ? "No notes found for: " + query : result.trim();
    }
}
