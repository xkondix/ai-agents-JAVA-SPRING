# claude-mcp-server

MCP server exposing tools for reading, searching and editing files in the
ai-agents-JAVA-SPRING project. Wired to **Claude Desktop** over STDIO, and used
as a live demonstration of an MCP server written in Java/Spring.

Renamed from `code-mcp-server`. The name change also changed
`spring.application.name`, which becomes `service.name` in OTLP — traces, logs
and metrics from this module appear under the new service name, not the old one.

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

`spring-boot-starter-parent` is not cosmetic here: it sets `-parameters`.
Without that flag the generated JSON schema publishes arguments as `arg0`/`arg1`
instead of `path`/`content`, and every argument arrives as `null` with **no
error in the log**. `@McpToolParam` has no `name` attribute, so reflection is
the only source of parameter names. `ToolSchemaContractTest` asserts this.

## Building

```bash
cd claude-mcp-server
mvn clean package
# JAR: target/claude-mcp-server-1.0.0-SNAPSHOT.jar
```

> Configuration is baked into the JAR at build time. Editing
> `src/main/resources/application.yml` changes nothing until you rebuild — a
> classic surprise when the server keeps using old settings after a restart.
> The same applies to restarting Claude Desktop: it relaunches the **existing**
> JAR, so a restart alone never picks up source changes.

> If `mvn clean` succeeds but `package` fails, `target/` is now empty and Claude
> Desktop reports `Error: Unable to access jarfile ...` on the next restart.
> That message means the build did not finish — it is not a server problem.

### Opening it in IntelliJ

Because the module is out of the reactor, IntelliJ's Maven import never sees it:
`src/main/java` is not marked as a source root, packages render as plain folders
and `.java` files get no code insight. Fix it once via **Maven tool window → +
(Add Maven Project) → `claude-mcp-server/pom.xml`**, which registers it as a
second Maven project in the same window. "Mark Directory as → Sources Root"
looks like it works but gives no classpath, so every Spring import stays red.

This is the real cost of keeping the module standalone; both benefits are
available at once, just not automatically.

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
- Claude Desktop merges this file with servers registered in its Settings. If
  the application log shows two PIDs starting at once, look there — one JSON
  entry does not mean one process. Each registered server also gets its own
  client-side log at `%APPDATA%\Claude\logs\mcp-server-<name>.log`, so listing
  that directory shows exactly how many are wired up.

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
`@ConfigurationProperties(prefix = "claude-mcp")` on `ClaudeMcpProperties` — a
mismatch binds nothing, logs nothing, and silently falls back to the defaults
hardcoded in the record.

Failures are returned to the model as `ERROR: ...` text rather than thrown. A
readable message is more useful to a model than a protocol-level error it cannot
inspect — but it means error rate has to be derived from the result text, not
from exception counts (see the metrics section).

## Tests

```bash
mvn test
```

Three suites, none of which needs a Spring context — deliberately, because a
`@SpringBootTest` here would start a STDIO server that attaches to the test
runner's stdin.

| Test | Guards |
|---|---|
| `ToolSchemaContractTest` | the published wire contract: nine tools, explicit names, and **no argN parameters** |
| `FileServiceTest` | the sandbox: path traversal, extension allow-list, ignored dirs |
| `CodeToolsServiceTracingTest` | spans are SERVER-kind, tagged and ended; meters record both outcomes; tools still work with no `Tracer` bean |

`ToolSchemaContractTest` is the important one. The `arg0` regression bit three
times and was invisible every time — build green, context up, tools listed,
no error in the log. It is now a build failure.

## Observability — all three signals

Everything works over STDIO because OTLP export is outgoing only. One port,
three paths, all on the Grafana LGTM stack:

| Signal | Endpoint | Lands in | Where to look |
|---|---|---|---|
| Traces | `:4318/v1/traces` | Tempo | Explore → Tempo → `{resource.service.name="claude-mcp-server"}` |
| Metrics | `:4318/v1/metrics` | Prometheus | the AI Agents dashboard |
| Logs | `:4318/v1/logs` | Loki | Explore → Loki → `{service_name="claude-mcp-server"}` |

When debugging an empty panel, query the bare stream selector first and only
then add a `trace_id` filter. "No logs found" for a filtered query and for an
unfiltered one are different diagnoses, and the widened time range matters too —
spans are batched (~5 s) and metrics are pushed on a 30 s step.

Spring Boot 4 moved tracing auto-configuration out of Actuator, and the OTLP
properties moved with it:

| | Boot 3 | Boot 4 |
|---|---|---|
| traces | `management.otlp.tracing.endpoint` | `management.opentelemetry.tracing.export.otlp.endpoint` |
| logs | — | `management.opentelemetry.logging.export.otlp.endpoint` |
| metrics | `management.otlp.metrics.export.url` | unchanged (Micrometer registry, not the OTel SDK) |
| dependency | actuator + bridge + exporter | `spring-boot-starter-opentelemetry` **+ actuator, see below** |

The old hand-picked dependency set put the Micrometer Tracing API on the
classpath with nothing to auto-configure it, so no `Tracer` bean existed and
constructor injection failed the whole context at startup. `CodeToolsService`
now resolves the tracer through `ObjectProvider` and degrades to "no spans,
tools still work" if it is ever absent again. `MeterRegistry` is resolved the
same way, falling back to a `SimpleMeterRegistry`.

### Actuator is required — for log export, not for endpoints

This module has no web server, so it exposes no actuator endpoint and never
will. `spring-boot-starter-actuator` is in the POM for exactly one reason: a
`@ConditionalOnClass`. Compare the two auto-configurations in Boot 4.0.0:

```java
@ConditionalOnClass({ OtelTracer.class, SdkTracerProvider.class,
                      OpenTelemetry.class, OtlpHttpSpanExporter.class })
class OtlpTracingAutoConfiguration          // no actuator

@ConditionalOnClass({ ConditionalOnEnabledLoggingExport.class,
                      OpenTelemetry.class, SdkLoggerProvider.class })
class OtlpLoggingAutoConfiguration          // FIRST condition is an actuator class
```

`ConditionalOnEnabledLoggingExport` ships in `spring-boot-actuator-autoconfigure`.
Drop actuator and OTLP **logging** auto-configuration silently backs off while
traces and metrics keep working — an asymmetry that is easy to miss precisely
because two of the three signals are fine.

The failure is completely quiet: the endpoint property still binds, the
`OpenTelemetry` bean still exists, `OpenTelemetryAppender.install()` still runs
and the appender still accepts records — but the SDK has no `LoggerProvider`
behind it, so every record is dropped. Loki simply stays empty.

Removing this dependency "because there is no HTTP server" is therefore a
one-line way to lose all logs with no error message.

### Traces

Each tool call becomes a `mcp_tool <name>` span carrying `gen_ai.tool.name`,
`gen_ai.operation.name=execute_tool`, `mcp.tool.args`, `mcp.tool.result.length`
and `framework=spring-ai-mcp-server` — the same label the agent modules use, so
the existing Grafana panels can slice by it. `mcp.transport` is a *resource*
attribute, set once for the whole service rather than repeated per span.

The span kind is **SERVER**, created via `tracer.spanBuilder().kind(...)` rather
than `tracer.nextSpan()`. This is not pedantry: Tempo's metrics generator builds
the service graph and the RED metrics from span kind, and as `INTERNAL` — the
default — this service never appeared as a node and generated no RED metrics
at all.

### Metrics

There is no agent loop here to instrument, so Spring AI emits nothing; the three
meters below are recorded by hand in `CodeToolsService`.

| Meter | Type | Tags |
|---|---|---|
| `mcp.tool.calls` | counter | `tool`, `outcome`, `framework` |
| `mcp.tool.duration` | timer | `tool`, `outcome`, `framework` |
| `mcp.tool.result.size` | distribution summary (chars) | `tool`, `framework` |

`outcome` is derived from the `ERROR:` prefix of the returned text, because
failures never propagate as exceptions. Counting thrown exceptions here would
report a permanent 0% error rate while the model reads failures all day.

`result.size` is the interesting one: it measures how many characters each tool
feeds back into the model's context, which is the real cost of an MCP call and
is invisible in latency alone.

### Logs need two extra pieces

Boot 4 auto-configures the logging SDK and its OTLP exporter, but does **not**
connect Logback to it. Two things are ours:

1. `logback-spring.xml` declares the `OpenTelemetryAppender` (and, just as
   importantly, declares **no console appender** — stdout belongs to JSON-RPC).
2. `OpenTelemetryAppenderInitializer` calls `OpenTelemetryAppender.install(...)`,
   which arms it. Until then the appender is inert.

Skip either one and the endpoint still binds, the SDK still starts, and not a
single line reaches Loki — with no error anywhere.

`logback-spring.xml` also routes `io.opentelemetry` and `io.micrometer` to the
file appender only, with `additivity="false"`. Both loggers report on the export
pipeline itself; with the OTEL appender attached to root, a "connection refused"
warning would be queued for export to the very endpoint that just refused it,
and each failure would generate another. Telemetry must not report on itself.

The appender comes from `io.opentelemetry.instrumentation`, a group Boot does
**not** version-manage. The pom pins `2.21.0-alpha` because that instrumentation
release targets OTel SDK `1.55.0`, the version Boot 4.0.0 ships. Re-check that
pairing when bumping Boot.

### Trace correlation

Since every trace starts here (no client sends `traceparent`), Tempo shows
one-span traces with `Services: 1`. That is correct, not broken propagation —
STDIO has no headers to carry W3C context. The HTTP-based `spring-ai-agent-mcp`
module does propagate it and shows `Services: 2`; the contrast between the two
is the point.

The `[TOOL] ...` log line is emitted **inside** the span scope. When it sat
above `tracer.withSpan(...)` it came out with an empty correlation field — one
orphan line per call, invisible when filtering Loki by trace id. The Micrometer
scope is a `ThreadLocal`, so only code between `withSpan()` and `close()` gets
the ids into the MDC.

With all of the above in place, **Logs for this span** in Tempo resolves to the
exact `[TOOL] ...` line of that call.

## Logs on disk

```
C:\Users\konra\Desktop\ai-agents-JAVA-SPRING\logs\claude-mcp-server.log
```

The file appender stays even with OTLP logging enabled: if the LGTM stack is
down, the file is the only record, and it is where you confirm a rebuild — look
for all nine tools registering and for the OTLP exporter lines.

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
