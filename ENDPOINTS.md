# Endpoints & Dependencies Reference

Quick reference for every HTTP endpoint, MCP tool, and observability endpoint
in the project. Companion to `README.md`.

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
| POST | `/api/v1/mcp/chat` | Orchestrator delegating to mcp-server (8081) and code-mcp-server (8086) |

### spring-ai-agent-local (port 8084)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/agent/chat` | Chat via ChatClient + local @Tool |
| POST | `/api/v1/agent/chat/approval` | Chat variant demonstrating the Approval Flow |

### spring-ai-agent-mcp (port 8085)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/mcp/chat` | Orchestrator with autoconfigured MCP clients (yml) |

### mcp-server (port 8081) — Approval REST API
| Method | Path | Description |
|--------|------|-------------|
| GET | `/approvals` | List pending approval requests |
| POST | `/approvals/{id}/approve` | Approve — unblocks the waiting tool call (returns true) |
| POST | `/approvals/{id}/reject` | Reject — unblocks the waiting tool call (returns false) |

### code-mcp-server (port 8086, only when transport=SYNC_HTTP_SSE)
| Method | Path | Description |
|--------|------|-------------|
| GET | `/approvals` | List pending approval requests |
| POST | `/approvals/{id}/approve` | Approve pending file operation |
| POST | `/approvals/{id}/reject` | Reject pending file operation |

> In STDIO mode (Claude Desktop) code-mcp-server has **no** HTTP endpoints —
> Tomcat never starts (`WebApplicationType.NONE`), which is why the Approval
> Flow is demonstrated on mcp-server instead.

### MCP protocol endpoints
| Server | Endpoint | Transport |
|--------|----------|-----------|
| mcp-server | `http://localhost:8081/mcp` | SYNC_HTTP_SSE |
| code-mcp-server | `http://localhost:8086/mcp` | SYNC_HTTP_SSE (or STDIO for Claude Desktop) |

---

## 2. MCP Tools

### mcp-server (ai-sandbox-mcp-server)
| Tool | Approval | Description |
|------|----------|-------------|
| `get_game_stats` | — | Mock game statistics |
| `get_weather` | — | Mock weather for a city |
| `search_notes` | — | Keyword search in the in-memory knowledge base |
| `save_note` | ✅ required | Blocks on human approval (Chat UI / REST) |
| `delete_note` | ✅ required | Blocks on human approval |

### code-mcp-server (ai-agents-code-server)
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

Guarded by `code-mcp.allowed-extensions` and `code-mcp.ignored-dirs`
(see `code-mcp-server/src/main/resources/application.yml`).

---

## 3. Observability Endpoints

Every Spring module exposes (management.endpoints.web.exposure):

| Path | Purpose |
|------|---------|
| `/actuator/health` | Liveness — polled by chat-ui (`useAgentHealth`) |
| `/actuator/info` | Build info |
| `/actuator/prometheus` | Prometheus scrape endpoint — custom Counters land here |
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

- **Traces (spans)** → Grafana → Explore → **Tempo**. GenAI Semantic Conventions
  (`gen_ai.provider.name`, `gen_ai.request.model`, `gen_ai.usage.input_tokens`,
  `gen_ai.usage.output_tokens`, `gen_ai.response.finish_reasons`) are attached
  to each LLM-call span by Spring AI 1.1+.
- **Counters / metrics** → Prometheus (`:9090`) or Grafana dashboards.
  Custom `Counter`/`Timer` beans registered on `MeterRegistry` are unaffected
  by the actuator-filtering predicate below.
- **Actuator noise filter**: `ObservabilityAutoConfiguration` (module `common`)
  registers an `ObservationPredicate` that drops observations for
  `/actuator/**` requests — no spans, no `http.server.requests` samples for
  health-check polling. Configurable via `xkondix.observability.excluded-path-prefixes`.
- Known limitation: MCP client → server calls do **not** propagate W3C trace
  context yet, so mcp-server spans appear as separate traces in Tempo
  (correlate by time). A2A + Trace Context is a Part 2 topic.

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
| chat-ui | 3000 | React UI (`/` chat, `/approvals` approval inbox) |

---

## 4. Module Dependency Graph

```
                       ┌───────────┐
                       │  common   │  DTOs + ObservabilityAutoConfiguration
                       └─────▲─────┘
        ┌───────────┬────────┼─────────┬─────────────┬────────────┐
        │           │        │         │             │            │
   raw-agent  lc4j-local  lc4j-mcp  spring-ai-local  spring-ai-mcp  mcp-server
     (8090)     (8082)     (8083)      (8084)          (8085)        (8081)
                              │                            │            ▲
                              └──────── MCP over HTTP ─────┴────────────┤
                                                                        │
                                                              code-mcp-server (8086)
                                                              (also STDIO ⇄ Claude Desktop)
```

Per-module dependencies (managed by parent BOMs — Spring Boot 3.5,
LangChain4j 1.16.3, Spring AI 1.1.4):

| Module | Key dependencies |
|--------|------------------|
| common | spring-context, jackson, jakarta.validation; optional: spring-boot-autoconfigure, spring-web, micrometer-observation |
| raw-agent | starter-web, jackson (java.net.http — no AI framework) |
| langchain4j-agent-local | langchain4j, langchain4j-spring-boot-starter, ollama starter |
| langchain4j-agent-mcp | + langchain4j-mcp (MCP client, Streamable HTTP) |
| spring-ai-agent-local | spring-ai ollama starter |
| spring-ai-agent-mcp | + spring-ai MCP client starter (connections in yml) |
| mcp-server | spring-ai MCP server starter (SYNC_HTTP_SSE), virtual threads |
| code-mcp-server | spring-ai MCP server starter (STDIO/HTTP), file-system tools |
| all modules | starter-actuator, springdoc, micrometer-tracing-bridge-otel, otlp exporter, prometheus registry (from parent) |
