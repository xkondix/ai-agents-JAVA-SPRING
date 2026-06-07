package com.xkondix.mcpserver.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class KnowledgeBaseTools {

    private final Map<String, String> notes = new ConcurrentHashMap<>();

    public McpServerFeatures.SyncToolSpecification getSaveNoteTool() {

        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "title":   { "type": "string", "description": "Note title" },
                    "content": { "type": "string", "description": "Note content" }
                  },
                  "required": ["title", "content"]
                }
                """;

        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "save_note",
                        "Save a note to the knowledge base. Returns the note ID.",
                        schema),
                (exchange, args) -> {
                    String title   = (String) args.get("title");
                    String content = (String) args.get("content");
                    String id      = "note-" + Instant.now().toEpochMilli();
                    notes.put(id, "[" + title + "] " + content);
                    log.info("Note saved: id={} title={}", id, title);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent("Saved: " + id)))
                            .build();
                }
        );
    }

    public McpServerFeatures.SyncToolSpecification getSearchNotesTool() {

        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "query": { "type": "string", "description": "Search keyword" }
                  },
                  "required": ["query"]
                }
                """;

        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "search_notes",
                        "Search notes in the knowledge base by keyword.",
                        schema),
                (exchange, args) -> {
                    String q = (String) args.get("query");
                    String result = notes.entrySet().stream()
                            .filter(e -> e.getValue().toLowerCase()
                                    .contains(q.toLowerCase()))
                            .map(e -> e.getKey() + ": " + e.getValue())
                            .reduce("", (a, b) -> a + "\n" + b);
                    String out = result.isBlank()
                            ? "No notes found for: " + q : result;
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(out)))
                            .build();
                }
        );
    }
}