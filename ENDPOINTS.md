# Endpoints & Dependencies Reference

Quick reference for every HTTP endpoint, MCP tool, and observability endpoint
in the project. Companion to `README.md` and `PATTERNS.md`.

---

## 1. REST Endpoints (business)

### raw-agent (port 8090)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/agent/chat` | Chat through the hand-written agent loop (no framework) |

### langchain4j-agent-local (port 8082)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/agent/raw` | Chat via raw LangChain4j loop (ChatModel called manually) |
| POST | `/api/v1/agent/aiservices` | Chat via AiServices (hidden loop) + local @Tool |

### langchain4j-agent-mcp (port 8083)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/mcp/chat` | Orchestrator delegating to mcp-server (8081); code-mcp-server optional via `lc4j.mcp.code-server.enabled` |

### spring-ai-agent-local (port 8084)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/agent/chat` | Chat via ChatClient + local @Tool |
| POST | `/api/v1/agent/chat/approval` | Chat variant demonstrating the Approval Flow |

### spring-ai-agent-mcp (port 8085)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/mcp/chat` | Orchestrator with autoconfigured MCP clients (yml) + W3C trace propagation |

### patterns-langchain4j (8087) and patterns-spring-ai (8088)
Mirror modules — **identical paths on both ports**, different framework
underneath. Details and diagrams: `PATTERNS.md`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/patterns/chain?season=&language=` | Prompt chaining; `language` defaults to `English`, accepts `Mixed` (blends all supported languages) |
| POST | `/api/v1/patterns/routing` | Routing — body is the question; the rumors branch is approval-gated |
| GET | `/api/v1/patterns/parallel` | Parallelization — scores rumor candidates concurrently |
| GET | `/api/v1/patterns/evaluator?season=` | Evaluator-optimizer loop (exit at score ≥ 0.8, max 4 iterations) |
| POST | `/api/v1/patterns/orchestrator` | Orchestrator-workers — body is the task |

### Approval REST API
Same contract everywhere, so the Chat UI talks to all sources the same way.

| Method | Path | Available on |
|--------|------|--------------|
| GET | `/approvals` | mcp-server (8081), patterns-langchain4j (8087), patterns-spring-ai (8088) |
| POST | `/approvals/{id}/approve` | as above — unblocks the waiting tool call |
| POST | `/approvals/{id}/reject` | as above — the tool returns a refusal to the model |

> code-mcp-server exposes the same API **only** in `SYNC_HTTP_SSE` mode.
> In STDIO mode (Claude Desktop) Tomcat never starts, so approvals are
> unavailable there by design — see `code-mcp-server/README.md`.

### MCP protocol endpoints
| Server | Endpoint | Transport |
|--------|----------|-----------|
| mcp-server | `http://localhost:8081/sse` + `/mcp/message` | SYNC_HTTP_SSE |
| code-mcp-server | `http://localhost:8086/sse` + `/mcp/message` | SYNC_HTTP_SSE (or STDIO for Claude Desktop) |

> Transport must match on both sides. A `StreamableHttp` client pointed at an
> SSE server answers **404** — that exact mismatch cost us an evening in
> langchain4j-agent-mcp.

---

## 2. Tools

### mcp-server (ai-sandbox-mcp-server) — over MCP
| Tool | Approval | Description |
|------|----------|-------------|
| `get_game_stats` | — | Mock game statistics |
| `get_weather` | — | Mock weather for a city |
| `search_notes` | — | Keyword search in the in-memory knowledge base |
| `save_note` | ✅ required | Blocks on human approval (Chat UI / REST) |
| `delete_note` | ✅ required | Blocks on human approval |

### code-mcp-server (ai-agents-code-server) — over MCP
| Tool | Approval | Description |
|------|----------|-------------|
| `read_file` | — | Read file content (relative to project root) |
| `list_files` | — | List directory entries |
| `get_project_structure` | — | Directory tree (configurable depth) |
| `search_in_files` | — | Case-insensitive text search |
| `write_file` | ✅ required | Overwrite existing file |
| `create_file` | ✅ required | Create new file (fails if exists) |
| `move_file` | ✅ required | Move/rename a file |
| `move_directory` | ✅ required | Move/rename a directory |
| `delete_file` | ✅✅ double | Delete a file (two approvals) |

Guarded by `code-mcp.allowed-extensions` (includes `.imports` for Spring
auto-configuration registration files) and `code-mcp.ignored-dirs`.

### patterns modules — local tools (AC Milan domain, `common/milan`)
| Tool | Approval | Description |
|------|----------|-------------|
| `getSquad(year)` | — | Squad for a season (2007, 2024) |
| `getPlayerStats(name)` | — | Position, shirt number, rating |
| `getTransfers(window)` | — | Transfers, optionally filtered |
| `getSecretRumors()` | ✅ required | Confidential rumors — blocks on human approval |

Same data in both modules, exposed with each framework's annotations
(`@Tool`/`@P` in LangChain4j, `@Tool`/`@ToolParam` in Spring AI).

---

## 3. Observability

Every Spring module exposes (management.endpoints.web.exposure):

| Path | Purpose |
|------|---------|
| `/actuator/health` | Liveness — polled by chat-ui (`useAgentHealth`, every 15 s) |
| `/actuator/info` | Build info |
| `/actuator/prometheus` | Prometheus scrape endpoint (pull) |
| `/actuator/metrics` | Micrometer metrics (human-readable) |

### Telemetry flow
```
module ──OTLP HTTP──▶ localhost:4318 ──▶ Grafana LGTM container
   │                                        ├── Tempo      (traces / spans)
   │                                        ├── Prometheus (metrics)
   │                                        └── Loki       (logs — NOT wired up;
   │                                             modules do not ship logs yet)
   └── /actuator/prometheus (pull model, if you scrape directly)
```

**Push, not pull.** The LGTM Prometheus does not scrape host applications — it
only stores what arrives over OTLP. Metrics therefore require
`micrometer-registry-otlp`; `micrometer-registry-prometheus` alone (pull) is
not enough. Traces worked without it because the trace exporter is a separate
artifact.

### Three instrumentation approaches, one dashboard
| Module | Spans | Metrics |
|--------|-------|---------|
| raw-agent | hand-written (`Tracer` API: `chat <model>`, `tool_call <name>` + `agent.loop.iteration`) | hand-written counters/timers in `LlmClient` |
| langchain4j-* | `GenAiMetricsChatModelListener` (chat spans) + `TracingToolProvider` (tool spans, patterns/mcp module) | same listener, `framework=langchain4j` |
| spring-ai-* | automatic (ChatClient observations) | automatic (`gen_ai.*`, `spring_ai_tool_*`) |

Metric names and tags are deliberately identical across all three, so every
module lands on the same Grafana panels; the `framework` tag separates them.

### Metric names (verified against Prometheus)
- `gen_ai_client_token_usage_total` — tags `gen_ai_request_model`, `gen_ai_token_type`
- `gen_ai_client_operation_milliseconds_{sum,count,bucket}` — LLM call duration
  (histogram buckets arrive from the OTLP exporter, so p95 works with no extra config)
- `spring_ai_tool_milliseconds_*` — tool executions (Spring AI modules only)
- `http_server_requests_milliseconds_*`, `jvm_*`

### Tracing notes
- GenAI Semantic Conventions on each LLM span: `gen_ai.request.model`,
  `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`,
  `gen_ai.response.finish_reasons`, `gen_ai.system`.
- **Actuator noise filter**: `ObservabilityAutoConfiguration` (module `common`)
  registers an `ObservationPredicate` that drops observations for
  `/actuator/**` — no spans, no `http.server.requests` samples for health-check
  polling. Configurable via `xkondix.observability.excluded-path-prefixes`.
- **MCP trace propagation**: `spring-ai-agent-mcp` propagates W3C trace context
  into MCP calls (`McpTracePropagationConfig`), so mcp-server spans appear
  inside the agent's trace (`Services: 2`). The MCP transport sends on its own
  worker threads, so the context is captured on the calling thread into
  `McpTransportContext` and injected from there.
- **Known gap**: `langchain4j-agent-mcp` does **not** propagate trace context
  (`HttpMcpTransport` sends no `traceparent`), so its mcp-server spans form
  separate traces — correlate by time. Kept deliberately as the A2A-vs-MCP
  contrast for Part 2.

### Dashboards
Provisioned from the repo: `grafana/provisioning/dashboards/` is mounted into
the LGTM container, so `docker compose up` brings up the "AI Agents —
Observability" dashboard (cost / performance / reliability / JVM). Dashboards
clicked together in the UI live only in the container's SQLite and vanish on
recreate — export them back into that folder to keep them.

### Infrastructure ports (docker-compose)
| Service | Port | Notes |
|---------|------|-------|
| Ollama | 11434 | Local LLM API (OpenAI-compatible under `/v1`) |
| Grafana | 3100 | admin/admin |
| OTLP gRPC / HTTP | 4317 / 4318 | Telemetry ingest |
| Prometheus | 9090 | Metrics UI |
| Tempo | 3201 | Trace store API |
| Chroma | 8000 | Vector store — reserved for the RAG part (unused for now) |
| Redis | 6379 | Reserved for permanent chat memory (unused for now) |
| Jira | 8080 | Integration demos (Part 2) |
| chat-ui | 3000 | React UI (`/` chat, `/approvals`, `/patterns`) |

---

## 4. Module Dependency Graph

```
                          ┌───────────┐
                          │  common   │
                          └─────▲─────┘
        ┌──────────┬────────────┼────────────┬───────────┬──────────────┐
   raw-agent   lc4j-local   lc4j-mcp   spring-ai-local  spring-ai-mcp  mcp-server
     (8090)      (8082)      (8083)        (8084)          (8085)        (8081)
                                │                            │             ▲
                                └───── MCP over HTTP ────────┴─────────────┤
                                                                           │
        patterns-langchain4j (8087)   patterns-spring-ai (8088)   code-mcp-server (8086)
                    └──────────── common ────────────┘            (also STDIO ⇄ Claude Desktop)
```

`common` provides:
- DTOs (`ChatRequest`, `ChatResponse`, …)
- `observability/` — `ObservabilityAutoConfiguration` (actuator filter),
  `GenAiMetricsChatModelListener` + `Lc4jGenAiMetricsAutoConfiguration`
  (GenAI metrics & spans for LangChain4j), registered via
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `approval/` — `HumanApprovalService` (generic `gate(...)`), `PendingApproval`,
  `ApprovalEndpoints`; wired explicitly by consumers, never auto-configured
- `milan/` — `MilanKnowledgeBase` (shared domain data for both patterns modules)
- `lang/` — `TranslationLanguages` (chaining target languages + `Mixed`)

Per-module dependencies (managed by parent BOMs — Spring Boot 3.5.0,
LangChain4j 1.16.3, Spring AI 1.1.4, Java 21):

| Module | Key dependencies |
|--------|------------------|
| common | spring-context, jackson, jakarta.validation; optional: spring-boot-autoconfigure, spring-web, micrometer-observation, micrometer-core, micrometer-tracing, langchain4j-core; provided: jakarta.servlet-api |
| raw-agent | starter-web, jackson (`java.net.http` — no AI framework) |
| langchain4j-agent-local | langchain4j + spring-boot-starter, ollama starter, open-ai starter (openrouter profile) |
| langchain4j-agent-mcp | + langchain4j-mcp (MCP client over SSE) |
| spring-ai-agent-local | spring-ai ollama + openai starters (provider chosen per profile) |
| spring-ai-agent-mcp | + spring-ai MCP client starter (connections in yml) |
| patterns-langchain4j | langchain4j + agentic (DSL: sequence/loop builders) |
| patterns-spring-ai | spring-ai starters (no workflow API — patterns in plain Java) |
| mcp-server | spring-ai MCP server starter (SYNC_HTTP_SSE), virtual threads |
| code-mcp-server | spring-ai MCP server starter (STDIO/HTTP), file-system tools |
| all modules | starter-actuator, springdoc, micrometer-tracing-bridge-otel, opentelemetry-exporter-otlp, micrometer-registry-prometheus, **micrometer-registry-otlp** (from parent) |
