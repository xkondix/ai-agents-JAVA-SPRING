# claude-mcp-server

MCP server exposing tools for reading, searching and editing files in the
ai-agents-JAVA-SPRING project. Wired to **Claude Desktop** over STDIO, and used
as a live demonstration of an MCP server written in Java/Spring.

Renamed from `code-mcp-server`. The name change also changed
`spring.application.name`, which becomes `service.name` in OTLP — traces from
this module appear in Tempo under the new service name, not the old one.

## Stack — deliberately different from the rest of the project

| | this module | rest of the project |
|---|---|---|
| Spring Boot | 4.0.0 | 3.5.0 |
| Spring AI | 2.0.0 | 1.1.4 |
| Parent POM | `spring-boot-starter-parent` | project parent |
| Reactor | **excluded** (standalone) | included |

It is the migration pilot for Spring AI 2.0. It stays out of the reactor build
so a broken migration cannot break the other nine modules — the parent POM keeps
the entry commented out:

```xml
<!-- <module>claude-mcp-server</module> -->
```

`spring-boot-starter-parent` is not cosmetic here: it sets
`-parameters`. Without that flag the generated JSON schema publishes arguments
as `arg0`/`arg1` instead of `path`/`content`, and every argument arrives as
`null` with **no error in the log**. `@McpToolParam` has no `name` attribute, so
reflection is the only source of parameter names.

## Building

```bash
cd claude-mcp-server
mvn clean package -DskipTests
# JAR: target/claude-mcp-server-1.0.0-SNAPSHOT.jar
```

> Configuration is baked into the JAR at build time. Editing
> `src/main/resources/application.yml` changes nothing until you rebuild — a
> classic surprise when the server keeps using old settings after a restart.
> The same applies to restarting Claude Desktop: it relaunches the **existing**
> JAR, so a restart alone never picks up source changes.

## Wiring into Claude Desktop

**File:** `C:\Users\konra\AppData\Roaming\Claude\claude_desktop_config.json`

```json
"claude-mcp-server": {
  "command": "java",
  "args": [
    "-Dlogging.file.name=C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\logs\\claude-mcp-server.log",
    "-jar",
    "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\claude-mcp-server\\target\\claude-mcp-server-1.0.0-SNAPSHOT.jar"
  ]
}
```

Then restart Claude Desktop. Notes:

- The absolute `-Dlogging.file.name` matters. Claude Desktop does not set the
  working directory to the project root, so the relative path in
  `application.yml` would put the log somewhere unhelpful.
- Do **not** pass `-Dspring.ai.mcp.server.transport=STDIO`. That property does
  not exist and never did; it was silently ignored. The real switch is
  `spring.ai.mcp.server.stdio: true`, already set in `application.yml`.
- The JSON key (`claude-mcp-server`) is the namespace Claude Desktop shows the
  tools under. Changing it renames every tool from the client's point of view.

## Transport: STDIO only

Verified against `McpServerProperties` in Spring AI 2.0.0:

```java
private boolean stdio = false;                              // default OFF
private ServerProtocol protocol = ServerProtocol.STREAMABLE;
public enum ServerProtocol { SSE, STREAMABLE, STATELESS }
```

Two things follow:

1. **`stdio` defaults to false** — it must be enabled explicitly.
2. **`ServerProtocol` has no `STDIO` constant.** STDIO is a transport toggle,
   not a protocol, so `protocol: STDIO` could never bind. This module never
   sets `protocol`.

HTTP transport lives in separate starters (`spring-ai-starter-mcp-server-webmvc`
/ `-webflux`) and is not used here — an embedded Tomcat would write to stdout
and corrupt the JSON-RPC stream.

## Available tools

| Tool | Description |
|---|---|
| `read_file` | Reads file content |
| `list_files` | Lists directory contents |
| `get_project_structure` | Project directory tree |
| `search_in_files` | Searches text in files |
| `write_file` | Overwrites an existing file |
| `create_file` | Creates a new file |
| `move_file` | Moves/renames a file |
| `move_directory` | Moves/renames a directory |
| `delete_file` | Deletes a file (`confirm` must be `DELETE`) |

Access is sandboxed by `claude-mcp.project-root` (path traversal is rejected),
`claude-mcp.allowed-extensions` and `claude-mcp.ignored-dirs` in
`application.yml`. The prefix must match
`@ConfigurationProperties(prefix = "claude-mcp")` on `CodeMcpProperties` — a
mismatch binds nothing, logs nothing, and silently falls back to the defaults
hardcoded in the record.

## No Approval Flow here — on purpose

Human-in-the-loop confirmation needs a second channel: something has to POST an
approve/reject decision while the tool call waits. Over STDIO there is no such
channel, and `future.get()` on the STDIO thread deadlocks the JSON-RPC stream
outright. Forcing a servlet container alongside STDIO trades that for a startup
slow enough to miss Claude Desktop's handshake, plus a real risk of Tomcat
writing to stdout.

So the approval demo lives on the HTTP-based **`mcp-server` (8081)** and both
patterns modules (8087, 8088), which are plain web applications with no STDIO
conflict. The shared gate is `HumanApprovalService` in `common`. This module
keeps none of that code — the contrast between the two transports *is* the
lesson.

## Observability

Tracing works fine over STDIO because OTLP export is outgoing only. Each tool
call becomes a `mcp_tool <name>` span with `gen_ai.tool.name` and
`framework=spring-ai-mcp-server`, so it lands on the same Grafana panels as the
agent modules.

Spring Boot 4 moved tracing auto-configuration out of Actuator, and the OTLP
properties moved with it:

| | Boot 3 | Boot 4 |
|---|---|---|
| traces | `management.otlp.tracing.endpoint` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| metrics | `management.otlp.metrics.export.url` | unchanged (Micrometer registry, not the OTel SDK) |
| dependency | actuator + bridge + exporter | `spring-boot-starter-opentelemetry` |

The old hand-picked dependency set put the Micrometer Tracing API on the
classpath with nothing to auto-configure it, so no `Tracer` bean existed and
constructor injection failed the whole context at startup. `CodeToolsService`
now resolves the tracer through `ObjectProvider` and degrades to "no spans,
tools still work" if it is ever absent again.

Since every trace starts here (no client sends `traceparent`), Tempo shows
one-span traces for this service. That is correct, not a broken propagation.

## Logs

```
C:\Users\konra\Desktop\ai-agents-JAVA-SPRING\logs\claude-mcp-server.log
```

Console logging is disabled two ways — `logging.console.enabled: false` (Boot 4
forces `CONSOLE_LOG_THRESHOLD=OFF`) and an explicit `logging.threshold.console`.
Anything printed to stdout corrupts the protocol, and Claude Desktop drops the
server without showing an error.

After a rebuild, the log is where you confirm the migration: look for all nine
tools registering, and for the OTLP exporter line. If a tool schema still shows
`arg0`, the problem is the compiler flag, not the annotations.
