# ai-agents-JAVA-SPRING — Multi-Agent Systems

Continuation of the "LangChain4j with Spring Boot" presentation series.
Author: Konrad Kowalczyk | xkondix

Companion docs: [`ENDPOINTS.md`](ENDPOINTS.md) (every endpoint, tool and metric)
· [`PATTERNS.md`](PATTERNS.md) (the five workflow patterns)
· [`OBSERVABILITY.md`](OBSERVABILITY.md) (three signals, and the failures that
stay silent).

**Stack:** Java 21 · Spring Boot 4.0.4 · Spring AI 2.0.0 · LangChain4j 1.16.3
· Jackson 3 · MCP over Streamable HTTP · Grafana LGTM.

## Project Structure

```
ai-agents-JAVA-SPRING/
├── pom.xml                    # Parent POM (dependency management + observability deps)
├── docker-compose.yml         # Grafana LGTM + Redis + Ollama
├── grafana/provisioning/      # Dashboards AND datasources, mounted into LGTM
├── common/                    # DTOs + observability + approval flow + AC Milan domain
├── mcp-server/                # Spring Boot MCP Server (port 8081)
├── claude-mcp-server/         # MCP Server for project file access — STDIO, standalone
├── raw-agent/                 # Pure agent loop — no AI framework (port 8090)
├── langchain4j-agent-local/   # LangChain4j: raw loop + AiServices + @Tool (port 8082)
├── langchain4j-agent-mcp/     # LangChain4j: MCP orchestrator (port 8083)
├── spring-ai-agent-local/     # Spring AI: ChatClient + Advisors + @Tool (port 8084)
├── spring-ai-agent-mcp/       # Spring AI: MCP orchestrator + trace propagation (port 8085)
├── patterns-langchain4j/      # 5 workflow patterns — Agentic DSL (port 8087)
├── patterns-spring-ai/        # 5 workflow patterns — hand-written loops (port 8088)
├── multimodal-lab/            # Image, speech, music, video — Spring AI only (port 8089)
└── chat-ui/                   # React UI: chat + approvals + Patterns Lab + Multimodal (port 3000)
```

`claude-mcp-server` is **outside the reactor**: it has its own
`spring-boot-starter-parent` and is built separately. A root `mvn clean
install` does not touch it.

## Quick Start

```bash
# 1. Start infrastructure — Grafana LGTM, Redis, Ollama
docker compose up -d

# 2. LLM provider — pick one
#    a) OpenRouter (DEFAULT profile) — set the key as an OS environment
#       variable, never in a file; restart the IDE/terminal afterwards
#       setx OPENROUTER_API_KEY "sk-or-..."     (Windows, then reopen the shell)
#    b) Local Ollama — pull the model and start modules with the local profile
docker exec -it ollama ollama pull llama3.1:8b

# 3. Build all modules
mvn clean install -DskipTests

# 4. Start modules (each in a separate terminal)
cd mcp-server              && mvn spring-boot:run     # start FIRST — MCP clients connect eagerly
cd raw-agent               && mvn spring-boot:run
cd langchain4j-agent-local && mvn spring-boot:run
cd langchain4j-agent-mcp   && mvn spring-boot:run
cd spring-ai-agent-local   && mvn spring-boot:run
cd spring-ai-agent-mcp     && mvn spring-boot:run
cd patterns-langchain4j    && mvn spring-boot:run
cd patterns-spring-ai      && mvn spring-boot:run
cd multimodal-lab          && mvn spring-boot:run

# 5. Start Chat UI
cd chat-ui && npm run dev
```

**Start `mcp-server` first.** Both MCP clients initialise eagerly during
context refresh, so a missing server prevents startup rather than degrading
it. Two failure shapes worth telling apart: server down gives a fast
`ConnectException`, while a transport mismatch hangs and dies after 20 s with
"Did not observe any item or terminal signal" — which reads like a network
problem and is not.

`multimodal-lab` needs **Redis** (chat memory and the gallery index). Without
it the conversation still works and the gallery falls back to a disk scan, so
the failure is partial rather than obvious.

### LLM profiles

Every agent module ships two Spring profiles; `openrouter` is the **default**.

| Profile | Provider | Model | Notes |
|---------|----------|-------|-------|
| `openrouter` (default) | OpenRouter (OpenAI-compatible) | `openai/gpt-4o-mini` | key from `OPENROUTER_API_KEY` |
| `local` | Ollama on this machine | `llama3.1:8b` | `mvn spring-boot:run -Dspring-boot.run.profiles=local` |

Gotchas worth remembering:

- **`/v1` belongs in the base-url for everyone now.** This changed in Spring AI
  2.0: the OpenAI module was rewritten on the official OpenAI SDK, the
  `completions-path` property is gone, and the SDK appends only
  `/chat/completions`. So all three stacks use `https://openrouter.ai/api/v1`.
  Getting it wrong produces `com.openai.errors.NotFoundException: 404: Unknown`,
  which says nothing about URLs.
- **Vendor prefix** in model names on OpenRouter (`openai/gpt-4o-mini`).
- **Model names are not translations of the upstream vendor's names.** For
  anything model-shaped, the catalogue endpoint is the source of truth:
  `curl "https://openrouter.ai/api/v1/models?output_modalities=speech"`.
  The documentation is a secondary source and has been wrong — see
  `multimodal-lab/src/main/resources/application.yml`.
- gemma3 is *not* used anywhere: it has no function calling, so tools silently
  turn into the model narrating that it "would use" them.

## Ports

| Module | Port | Description |
|--------|------|-------------|
| mcp-server | 8081 | MCP tools server (game stats, KB, weather) + Approval API |
| langchain4j-agent-local | 8082 | LangChain4j raw loop + AiServices |
| langchain4j-agent-mcp | 8083 | LangChain4j MCP orchestrator |
| spring-ai-agent-local | 8084 | Spring AI ChatClient + Advisors |
| spring-ai-agent-mcp | 8085 | Spring AI MCP orchestrator (trace propagation) |
| patterns-langchain4j | 8087 | Workflow patterns — Agentic DSL |
| patterns-spring-ai | 8088 | Workflow patterns — hand-written loops |
| multimodal-lab | 8089 | Image / speech / music / video, Spring AI only |
| raw-agent | 8090 | Pure agent loop — no AI framework |
| chat-ui | 3000 | React UI: chat, `/approvals`, `/patterns`, `/multimodal` |
| Grafana | 3100 | Observability dashboards (LGTM stack) |
| Prometheus | 9090 | Metrics UI (verify metric names here first!) |
| Tempo | 3201 | Trace store API |
| OTLP gRPC / HTTP | 4317 / 4318 | OpenTelemetry ingest |
| Redis | 6379 | Chat memory + gallery index for multimodal-lab |
| Ollama | 11434 | Local LLM API |

`claude-mcp-server` has **no port** — it speaks MCP over STDIO and is launched
by Claude Desktop, not by you.

> Chroma, Jira and a Postgres for it used to sit behind a `--profile extras`,
> reserved for RAG and integration demos that were never built. They are gone:
> reserved infrastructure is a maintenance surface, not a plan. `git log`
> remembers the definitions if a later part of the series needs one back.

## Agent Architecture

```
raw-agent               →  pure HTTP + Jackson, manual while-loop, zero AI frameworks
langchain4j-agent-local →  local @Tool methods (same process)
langchain4j-agent-mcp   →  MCP client → mcp-server (8081)
spring-ai-agent-local   →  local @Tool methods (same process)
spring-ai-agent-mcp     →  MCP client → mcp-server (8081), W3C trace context propagated
patterns-*              →  five workflow patterns over the AC Milan domain (see PATTERNS.md)
multimodal-lab          →  one router agent, five tools, four different provider protocols
```

The two MCP orchestrators differ **on purpose**: `spring-ai-agent-mcp`
propagates the trace context across the process boundary and
`langchain4j-agent-mcp` does not. The same tool call therefore produces one
trace with two services in one module and two separate traces in the other —
the clearest demonstration in the repo that context propagation is a client
implementation decision, not a property of MCP.

## MCP Servers

### mcp-server (port 8081)
Tools: `get_game_stats`, `get_weather`, `search_notes`,
`save_note` 🔒, `delete_note` 🔒 (🔒 = human approval required).

Uses `@McpTool` / `@McpToolParam` (Spring AI 2.0) with **explicit tool names**,
so the wire contract does not change when a method is renamed. Transport is
Streamable HTTP on `/mcp`; SSE is deprecated in Spring AI 2.0 and no longer
answers.

### claude-mcp-server (STDIO, no port)
Tools (no approval): `read_file`, `list_files`, `get_project_structure`, `search_in_files`
Tools (approval required): `write_file`, `create_file`, `move_file`, `move_directory`
Tools (double approval): `delete_file`

Runs over STDIO for Claude Desktop, where the Approval REST API cannot work —
see [`claude-mcp-server/README.md`](claude-mcp-server/README.md). Built
separately: `cd claude-mcp-server && mvn clean package`.

### Approval Flow (human-in-the-loop)
UI: http://localhost:3000/approvals — polls **mcp-server (8081)** and both
patterns modules (8087, 8088). The shared mechanism lives in
`common/approval`; a tool call blocks until you approve or reject, which is
visible in Tempo as a tool span growing in real time.

## Claude Desktop MCP Config

File: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "ai-agents-claude-server": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\claude-mcp-server\\target\\claude-mcp-server-1.0.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

No transport property is passed. `spring.ai.mcp.server.transport` does not
exist — the enum has no `STDIO` constant, and an unknown property is simply a
surplus property that Spring ignores. STDIO comes from the starter on the
classpath instead (see the module README).

## Framework Comparison

| Feature | raw-agent | LangChain4j | Spring AI |
|---------|-----------|-------------|-----------|
| Loop visibility | Fully visible | Hidden (AiServices / Agentic DSL) | Hidden (ToolCallingAdvisor) |
| Tool definition | Manual JSON | `@Tool` annotation | `@Tool` annotation |
| HTTP calls | `java.net.http` | Hidden | Hidden |
| Interceptors | Manual | Limited (listeners) | Advisor pattern |
| MCP client | N/A | Manual `@Bean` config | Autoconfigured from yml |
| Workflow API | N/A | Agentic DSL (sequence / loop / conditional) | none — plain Java |
| Multimodal | N/A | image only | image, speech, transcription |
| Observability | Hand-written spans & metrics | Listener (`common`) | Automatic — with one condition |
| Versions | — | 1.16.3 (`-spring-boot4-` starters, agentic beta26) | 2.0.0 |

**The condition on Spring AI's observability:** it is automatic only when the
`ChatClient` is built from the **injected** `ChatClient.Builder`. The static
`ChatClient.builder(chatModel)` hands the client an
`ObservationRegistry.NOOP`, which removes every span and metric below the call
without a word. That one line looked like a missing feature in Spring AI 2.0
for a full day.

## Observability

Grafana LGTM stack: http://localhost:3100 · OTLP: 4318 (HTTP) / 4317 (gRPC).
Dashboards **and datasources** are provisioned from `grafana/provisioning/`, so
they ship with the repo instead of living inside the container — anything
clicked together in the UI disappears on `docker compose down`.

Required per module: `spring-boot-starter-opentelemetry` (new in Boot 4 — it
replaces the hand-picked set of `micrometer-tracing-bridge-otel` +
`opentelemetry-exporter-otlp` + `micrometer-registry-otlp`) plus
`spring-boot-starter-actuator`. Metrics are **pushed**; LGTM scrapes nothing.

Actuator is not optional and not about endpoints:
`ConditionalOnEnabledLoggingExport` is an actuator class and gates OTLP **log**
export. Without actuator, logs back off silently while traces and metrics keep
working.

GenAI Semantic Conventions tracked per call:
- `gen_ai.provider.name`, `gen_ai.request.model`
- `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`
- `gen_ai.response.finish_reasons`

Full details, metric names and known gaps: [`OBSERVABILITY.md`](OBSERVABILITY.md)
and [`ENDPOINTS.md`](ENDPOINTS.md#3-observability).
