# Code MCP Server (Spring AI starter)

MCP Server exposing tools for reading, searching and editing files
in the ai-agents-JAVA-SPRING project.
Uses the official Spring AI MCP server starter approach.

## Building the JAR

```bash
cd code-mcp-server
mvn clean package -DskipTests
# JAR: target/code-mcp-server-1.0.0-SNAPSHOT.jar
```

## Connecting to Claude Desktop

**File:** `C:\Users\konra\AppData\Roaming\Claude\claude_desktop_config.json`

Add to the existing JSON (under mcpServers):

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

## Approval Flow

When Claude wants to perform a sensitive operation:
1. Server logs: `[APPROVAL REQUIRED] id=abc123 ...`
2. The log shows the URL to approve
3. Approve via REST or Chat UI:

```bash
POST http://localhost:8086/approvals/{id}/approve
POST http://localhost:8086/approvals/{id}/reject
GET  http://localhost:8086/approvals
```

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
