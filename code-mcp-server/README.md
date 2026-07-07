# Code MCP Server (Spring AI starter)

MCP Server exposing tools for reading, searching and editing files
in the ai-agents-JAVA-SPRING project.
Uses the official Spring AI MCP server starter approach.

This server is wired to **Claude Desktop** and is used as a live demonstration
of an MCP server written in Java/Spring — Claude can read, search and edit the
project files directly.

## Building the JAR

```bash
cd code-mcp-server
mvn clean package -DskipTests
# JAR: target/code-mcp-server-1.0.0-SNAPSHOT.jar
```

## Transport modes

This server can run in two transport modes. The mode is selected with the
`spring.ai.mcp.server.transport` property.

| Mode | Value | Used by | Approval REST API |
|---|---|---|---|
| **STDIO** (default) | `STDIO` | Claude Desktop | Not available* |
| **HTTP / SSE** | `SYNC_HTTP_SSE` | HTTP clients, mcp-remote bridge | Available on :8086 |

\* See "Approval Flow limitation in STDIO mode" below.

---

## Mode 1 — STDIO (default, used with Claude Desktop)

This is the default and recommended mode for this server. Claude Desktop
launches the JAR as a subprocess and talks to it over stdin/stdout.

**File:** `C:\Users\konra\AppData\Roaming\Claude\claude_desktop_config.json`

Add to the existing JSON (under `mcpServers`):

```json
"ai-agents-code": {
  "command": "java",
  "args": [
    "-Dspring.ai.mcp.server.transport=STDIO",
    "-Dlogging.file.name=C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\logs\\code-mcp-server.log",
    "-jar",
    "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\code-mcp-server\\target\\code-mcp-server-1.0.0-SNAPSHOT.jar"
  ]
}
```

Restart Claude Desktop — you will see a hammer icon in the chat window.

In STDIO mode Claude Desktop manages the server lifecycle for you: it starts
the JAR on launch and stops it on exit. No manual server start needed.

---

## Mode 2 — HTTP / SSE (optional, enables the Approval REST API)

In this mode the server runs as a normal Spring Boot web application with an
embedded Tomcat on port 8086. This makes the Approval REST API reachable, so a
human can approve/reject sensitive operations from the Chat UI.

Because **Claude Desktop's `claude_desktop_config.json` only accepts STDIO
servers** (a `url` field is silently dropped), connecting Claude Desktop to an
HTTP MCP server requires the `mcp-remote` stdio→HTTP bridge.

### Steps

1. **Start the server manually in HTTP mode** (separate terminal, before
   launching Claude Desktop):

   ```bash
   cd code-mcp-server
   java -Dspring.ai.mcp.server.transport=SYNC_HTTP_SSE -jar target/code-mcp-server-1.0.0-SNAPSHOT.jar
   # or: mvn spring-boot:run
   ```

   Tomcat starts on :8086. The Approval REST API is now reachable.

2. **Point Claude Desktop at it via the `mcp-remote` bridge.** Replace the
   STDIO entry above with this one (do NOT keep both — same server name):

   ```json
   "ai-agents-code": {
     "command": "npx",
     "args": ["mcp-remote", "http://localhost:8086/mcp", "--transport", "http-only"]
   }
   ```

   On Windows, if `npx` is not found (Claude Desktop launches with a minimal
   PATH), use the full path:

   ```json
   "command": "C:\\Program Files\\nodejs\\npx.cmd",
   ```

   If the bridge cannot connect, try the `/sse` endpoint instead of `/mcp`.

3. **Restart Claude Desktop.** It now talks STDIO to `mcp-remote`, which
   forwards over HTTP to the server on :8086.

### Workflow difference

| | STDIO mode | HTTP + mcp-remote |
|---|---|---|
| Who starts the server | Claude Desktop | You, manually, before launch |
| Startup order | Just launch Claude Desktop | 1. Start server 2. Launch Claude Desktop |
| After a code change | Rebuild JAR + restart Claude Desktop | Rebuild + restart server (Claude Desktop can stay open) |
| Approval REST API | Not available | Available on :8086 |

> **Note:** Requires Node.js installed (for `npx mcp-remote`). Remember to start
> the server manually before each session — add it to your quick-start checklist.

---

## Available tools

| Tool | Approval | Description |
|---|---|---|
| `read_file` | None | Reads file content |
| `list_files` | None | Lists directory contents |
| `get_project_structure` | None | Project directory tree |
| `search_in_files` | None | Searches text in files |
| `write_file` | **Required** | Overwrites a file |
| `create_file` | **Required** | Creates a new file |
| `move_file` | **Required** | Moves/renames a file |
| `delete_file` | **Double** | Deletes a file (irreversible) |

---

## Approval Flow limitation in STDIO mode

The Approval Flow (human-in-the-loop confirmation for write/create/move/delete)
requires a **running HTTP server** so the Chat UI can POST approve/reject to
`http://localhost:8086/approvals`.

In **STDIO mode** this is problematic:

- The Spring AI MCP STDIO starter defaults to `WebApplicationType.NONE`, so the
  embedded Tomcat does not start and the Approval REST endpoints are unreachable.
- Forcing `WebApplicationType.SERVLET` makes Tomcat start, but it slows startup
  past Claude Desktop's handshake timeout (~60s), and risks writing to stdout
  which corrupts the JSON-RPC stream.
- Additionally, `requestApproval()` blocks the STDIO thread on `future.get()`
  while waiting for a decision that can only arrive over a separate HTTP channel.

**Therefore, in this project the Approval Flow is demonstrated on the HTTP-based
`mcp-server` (port 8081) instead**, which is a pure HTTP MCP server with no
STDIO conflict. This `code-mcp-server` keeps the approval code as a reference,
but to run it live, use Mode 2 (HTTP + mcp-remote) above.

### Approval REST API (HTTP mode only)

```bash
GET  http://localhost:8086/approvals
POST http://localhost:8086/approvals/{id}/approve
POST http://localhost:8086/approvals/{id}/reject
```

---

## Logs

Logs go to file (not stdout — stdout is reserved for JSON-RPC):
```
C:\Users\konra\Desktop\ai-agents-JAVA-SPRING\logs\code-mcp-server.log
```

## Adding to Maven project

In the main `pom.xml`:
```xml
<module>code-mcp-server</module>
```
