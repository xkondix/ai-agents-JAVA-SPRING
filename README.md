# ai-agents-JAVA-SPRING — Multi-Agent Systems

Continuation of the "LangChain4j with Spring Boot" presentation series.
Author: Konrad Kowalczyk | xkondix
After Devoxx Poland 2026 — includes A2A, OpenTelemetry GenAI, LangGraph4j concepts.

Companion docs: [`ENDPOINTS.md`](ENDPOINTS.md) (every endpoint, tool and metric)
· [`PATTERNS.md`](PATTERNS.md) (the five workflow patterns).

## Project Structure

```
ai-agents-JAVA-SPRING/
├── pom.xml                    # Parent POM (dependency management + observability deps)
├── docker-compose.yml         # Ollama + Redis + Chroma + Jira + Grafana LGTM
├── grafana/provisioning/      # Dashboards shipped with the repo (mounted into LGTM)
├── common/                    # DTOs + observability + approval flow + AC Milan domain
├── mcp-server/                # Spring Boot MCP Server (port 8081)
├── code-mcp-server/           # MCP Server for project file access (port 8086, STDIO)
├── raw-agent/                 # Pure agent loop — no AI framework (port 8090)
├── langchain4j-agent-local/   # LangChain4j: raw loop + AiServices + @Tool (port 8082)
├── langchain4j-agent-mcp/     # LangChain4j: MCP orchestrator (port 8083)
├── spring-ai-agent-local/     # Spring AI: ChatClient + Advisors + @Tool (port 8084)
├── spring-ai-agent-mcp/       # Spring AI: MCP orchestrator + trace propagation (port 8085)
├── patterns-langchain4j/      # 5 workflow patterns — Agentic DSL (port 8087)
├── patterns-spring-ai/        # 5 workflow patterns — plain Java (port 8088)
└── chat-ui/                   # React UI: chat + approvals + Patterns Lab (port 3000)
```

## Quick Start

```bash
# 1. Start infrastructure (Ollama, Grafana LGTM, Redis, Chroma, Jira)
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

# 5. Start Chat UI
cd chat-ui && npm run dev
```

### LLM profiles

Every agent module ships two Spring profiles; `openrouter` is the **default**.

| Profile | Provider | Model | Notes |
|---------|----------|-------|-------|
| `openrouter` (default) | OpenRouter (OpenAI-compatible) | `openai/gpt-4o-mini` | key from `OPENROUTER_API_KEY`; ~0.7 s per call |
| `local` | Ollama on this machine | `llama3.1:8b` | `mvn spring-boot:run -Dspring-boot.run.profiles=local` |

Two gotchas worth remembering:
- **`/v1` in base-url**: Spring AI appends it itself (`https://openrouter.ai/api`),
  LangChain4j and raw-agent need it in the URL (`.../api/v1`).
- **Vendor prefix** in model names on OpenRouter (`openai/gpt-4o-mini`).
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
| code-mcp-server | 8086 | MCP tools for project file access (STDIO by default) |
| patterns-langchain4j | 8087 | Workflow patterns — Agentic DSL |
| patterns-spring-ai | 8088 | Workflow patterns — plain Java |
| raw-agent | 8090 | Pure agent loop — no AI framework |
| chat-ui | 3000 | React UI: chat, `/approvals`, `/patterns` |
| Grafana | 3100 | Observability dashboards (LGTM stack) |
| Prometheus | 9090 | Metrics UI (verify metric names here first!) |
| Tempo | 3201 | Trace store API |
| OTLP gRPC / HTTP | 4317 / 4318 | OpenTelemetry ingest |
| Ollama | 11434 | Local LLM API |
| Jira | 8080 | Local Jira instance (integration demos) |
| Chroma / Redis | 8000 / 6379 | Reserved for RAG and permanent memory |

## Agent Architecture

```
raw-agent               →  pure HTTP + Jackson, manual while-loop, zero AI frameworks
langchain4j-agent-local →  local @Tool methods (same process)
langchain4j-agent-mcp   →  MCP client → mcp-server (8081); code-mcp-server optional
spring-ai-agent-local   →  local @Tool methods (same process)
spring-ai-agent-mcp     →  MCP clients → mcp-server (8081), W3C trace context propagated
patterns-*              →  five workflow patterns over the AC Milan domain (see PATTERNS.md)
```

## MCP Servers

### mcp-server (port 8081)
Tools: `get_game_stats`, `get_weather`, `search_notes`,
`save_note` 🔒, `delete_note` 🔒 (🔒 = human approval required).

### code-mcp-server (port 8086)
Tools (no approval): `read_file`, `list_files`, `get_project_structure`, `search_in_files`
Tools (approval required): `write_file`, `create_file`, `move_file`, `move_directory`
Tools (double approval): `delete_file`

Runs over STDIO for Claude Desktop, where the Approval REST API cannot work —
see `code-mcp-server/README.md`.

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
    "ai-agents-code": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.transport=STDIO",
        "-jar",
        "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\code-mcp-server\\target\\code-mcp-server-1.0.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

## Framework Comparison

| Feature | raw-agent | LangChain4j | Spring AI |
|---------|-----------|-------------|-----------|
| Loop visibility | Fully visible | Hidden (AiServices) | More explicit (ChatClient) |
| Tool definition | Manual JSON | `@Tool` annotation | `@Tool` annotation |
| HTTP calls | `java.net.http` | Hidden | Hidden |
| Interceptors | Manual | Limited (listeners) | Advisor pattern |
| MCP client | N/A | Manual `@Bean` config | Autoconfigured from yml |
| Workflow API | N/A | Agentic DSL (sequence/loop) | none — plain Java |
| Observability | Hand-written spans & metrics | Listener (`common`) | Automatic |
| Versions | — | 1.16.3 (agentic beta26) | 1.1.4 |

## Observability

Grafana LGTM stack: http://localhost:3100 · OTLP: 4318 (HTTP) / 4317 (gRPC).
Dashboards are provisioned from `grafana/provisioning/dashboards/`, so they
ship with the repo instead of living inside the container.

Required per module: `micrometer-tracing-bridge-otel`,
`opentelemetry-exporter-otlp`, `micrometer-registry-otlp` (metrics are **pushed**,
LGTM scrapes nothing) plus a few lines of `management.*` config.

GenAI Semantic Conventions tracked per call:
- `gen_ai.provider.name`, `gen_ai.request.model`
- `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`
- `gen_ai.response.finish_reasons`

Full details, metric names and known gaps: [`ENDPOINTS.md`](ENDPOINTS.md#3-observability).
