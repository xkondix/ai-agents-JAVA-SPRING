# Endpoints & Dependencies Reference

Quick reference for every HTTP endpoint, MCP tool, and observability signal
in the project. Companion to `README.md`, `PATTERNS.md` and `OBSERVABILITY.md`.

Stack: Java 21 · Spring Boot 4.0.4 · Spring Framework 7.0.6 · Spring AI 2.0.0 ·
LangChain4j 1.16.3 (`-spring-boot4-` starters) · Jackson 3 · springdoc 3.0.3 ·
MCP over Streamable HTTP · Grafana LGTM 0.32.0.

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
| POST | `/api/v1/mcp/chat` | Orchestrator delegating to mcp-server (8081) over Streamable HTTP. Memory keyed by `conversationId` / `userId` from the request; none = one-off conversation |

### spring-ai-agent-local (port 8084)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/agent/chat` | Chat via ChatClient + local @Tool |
| POST | `/api/v1/agent/chat/approval` | Chat variant demonstrating the Approval Flow |

### spring-ai-agent-mcp (port 8085)
| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/mcp/chat` | Orchestrator with autoconfigured MCP client (yml) + W3C trace propagation into mcp-server |

### patterns-langchain4j (8087) and patterns-spring-ai (8088)
Mirror modules — **identical paths on both ports**, different framework
underneath. Details and diagrams: `PATTERNS.md`.

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/patterns/chain?season=&language=` | Prompt chaining; `language` defaults to `English`, accepts `Mixed` |
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

> claude-mcp-server has **no** approval flow by design: over STDIO there is no
> second channel for a human decision, and blocking the JSON-RPC thread would
> deadlock the stream. See `claude-mcp-server/README.md`.

### MCP protocol endpoints
| Server | Endpoint | Transport |
|--------|----------|-----------|
| mcp-server | `POST http://localhost:8081/mcp` | Streamable HTTP (`spring.ai.mcp.server.protocol: STREAMABLE`) |
| claude-mcp-server | stdin/stdout | STDIO, launched by Claude Desktop |

> Transport must match on both sides. Both agents initialise their MCP client
> **eagerly at startup**, so a mismatch or a server that is not running does
> not degrade them to "no tools" — it stops them from starting.
> **Start mcp-server before langchain4j-agent-mcp and spring-ai-agent-mcp.**
> SSE is `@Deprecated(forRemoval = true)` in Spring AI 2.0 and is not used.

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

Every call is wrapped by `McpToolTelemetry` (module `common`): a SERVER span
`mcp_tool <name>` plus `mcp.tool.*` meters — see §3.

### claude-mcp-server (ai-agents-claude-server) — over MCP / STDIO
| Tool | Description |
|------|-------------|
| `read_file` | Read file content (relative to project root) |
| `list_files` | List directory entries |
| `get_project_structure` | Directory tree (configurable depth) |
| `search_in_files` | Case-insensitive text search |
| `write_file` | Overwrite existing file |
| `create_file` | Create new file (fails if exists) |
| `move_file` | Move/rename a file |
| `move_directory` | Move/rename a directory |
| `delete_file` | Delete a file (`confirm` must be `DELETE`) |

Sandbox (`claude-mcp.*` properties): every path is resolved under
`project-root` and rejected if it escapes it (lexically **and** via
`toRealPath()` against symlinks); the extension allow-list applies to read,
write, create, **move and delete** on every path involved; `ignored-dirs`
(`.git`, `target`, …) are unreachable by direct path, not only skipped in
walks; reads are capped at 2 MB.

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

Full write-up in `OBSERVABILITY.md`; this section is the reference table.

Every Spring module exposes (`management.endpoints.web.exposure`):

| Path | Purpose |
|------|---------|
| `/actuator/health` | Liveness — polled by chat-ui (`useAgentHealth`, every 15 s); excluded from traces/metrics by `ObservabilityAutoConfiguration` |
| `/actuator/info` | Build info |
| `/actuator/prometheus` | Prometheus scrape endpoint (pull, local debugging only) |
| `/actuator/metrics` | Micrometer metrics (human-readable) |

### Telemetry flow
```
module ──OTLP HTTP :4318──▶ Grafana LGTM (docker, pinned 0.32.0, volume lgtm-data:/data)
                              ├── Tempo      traces   management.opentelemetry.tracing.export.otlp.endpoint
                              ├── Loki       logs     management.opentelemetry.logging.export.otlp.endpoint
                              │                       + Logback→OTel bridge in `common`
                              │                         (logback-spring.xml + OtelLoggingBridgeAutoConfiguration)
                              └── Prometheus metrics  management.otlp.metrics.export.url (push every 60 s)
```

**Push, not pull.** Nothing scrapes the applications. Consequences for
Grafana: every `rate()`/`increase()` query in the dashboard carries
`interval: 1m`, and the Prometheus datasource is provisioned with
`timeInterval: 60s`; without both, `$__rate_interval` is 60 s and rate panels
look empty at short ranges.

### Three instrumentation approaches, one dashboard
| Module | Spans | Metrics |
|--------|-------|---------|
| raw-agent | hand-written (`Tracer` API: `chat <model>`, `tool_call <n>` + `agent.loop.iteration`) | hand-written in `LlmClient`, pre-registered at 0 on startup |
| langchain4j-* | `GenAiMetricsChatModelListener` (chat spans, optional `gen_ai.prompt`/`gen_ai.completion`) + `TracingToolProvider` (tool spans) | same listener, `framework=langchain4j`, pre-registered at 0 on startup |
| spring-ai-* | automatic — `chat_client → tool_calling → advisors → chat → POST` plus `execute_tool <n>` (`spring.ai.tool.*` attributes) | automatic (`gen_ai.*`, `spring_ai_tool_*`) |
| mcp-server | `mcp_tool <n>` (SERVER kind, `McpToolTelemetry`) | `mcp_tool_calls_total`, `mcp_tool_duration_milliseconds_*`, `mcp_tool_payload_size_chars_*` |
| claude-mcp-server | same, own copy in `CodeToolsService` | same names, `framework=spring-ai` |

**Spring AI observations exist only with the auto-configured `ChatClient.Builder`.**
`ChatClient.builder(chatModel)` hard-codes `ObservationRegistry.NOOP` and
silently drops every ChatClient-level observation, including tool spans and
`spring_ai_tool_*` — all four ChatClients in the project inject the builder.

### Metric names (verified against Prometheus, 2026-09-02)
- `gen_ai_client_token_usage_total` — tags `job`, `gen_ai_request_model`, `gen_ai_token_type`
  (`framework` present for raw/langchain4j, absent for Spring AI → group by `job`)
- `gen_ai_client_operation_milliseconds_{sum,count,bucket}` — LLM call duration.
  Micrometer names differ (`gen.ai.client.operation` raw/LC4j, `gen_ai.client.operation`
  Spring AI); Prometheus normalises both. Buckets `250…60000 ms` come from the
  `MeterFilter` in `ObservabilityAutoConfiguration` — without it only `le="+Inf"`
  exists and `histogram_quantile` returns nothing.
- `gen_ai_client_tool_requests_total` — tool calls the model asked for (raw, LC4j)
- `spring_ai_tool_milliseconds_*` — tool executions (Spring AI), tag `spring_ai_tool_definition_name`
- `mcp_tool_*` — MCP server side, tags `tool`, `outcome`, `direction`
- `http_server_requests_milliseconds_*`, `jvm_*`

No `*_max` series reaches Prometheus over OTLP; panels use sum/count.

### Tracing notes
- GenAI Semantic Conventions on each LLM span: `gen_ai.request.model`,
  `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`,
  `gen_ai.response.finish_reasons`, `gen_ai.system`.
- **Prompt/completion content**: Spring AI logs it (`spring.ai.chat.observations.log-prompt`
  / `log-completion` → Loki, trace-correlated); LangChain4j puts it on the span
  (`xkondix.observability.genai.include-prompt` / `include-completion` → Tempo).
  Same data, two signals — deliberate contrast.
- **MCP trace propagation**: `spring-ai-agent-mcp` propagates W3C trace context
  into MCP calls (`McpTracePropagationConfig`), so mcp-server spans appear inside
  the agent's trace (`Services: 2`). The MCP transport sends on its own worker
  threads, so the context is captured on the calling thread into
  `McpTransportContext` and injected from there.
- **Known gap, kept on purpose**: `langchain4j-agent-mcp` does **not** propagate
  trace context, so its mcp-server spans form separate traces — correlate by
  time. The A2A-vs-MCP contrast for Part 2.

### Dashboards
Provisioned from `grafana/provisioning/dashboards/` (reloaded every 30 s) and
`grafana/provisioning/datasources/`; Grafana opens on the project dashboard
(`GF_DASHBOARDS_DEFAULT_HOME_DASHBOARD_PATH`). Rows: Preflight · Cost ·
Performance · MCP servers · Traces · Reliability · JVM.

### Infrastructure ports (docker-compose)
Default profile:

| Service | Port | Notes |
|---------|------|-------|
| Ollama | 11434 | Local LLM API (`local` Spring profile only) |
| Grafana | 3100 | admin/admin |
| OTLP gRPC / HTTP | 4317 / 4318 | Telemetry ingest |
| Prometheus | 9090 | Metrics UI |
| Tempo | 3201 | Trace store API |

`--profile extras` (unused by any module today): Redis 6379, Chroma 8000, Jira 8080.

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
                                └──── MCP / Streamable HTTP ─┴─────────────┘

        patterns-langchain4j (8087)   patterns-spring-ai (8088)
                    └──────────── common ────────────┘

   claude-mcp-server — standalone (own parent, outside the reactor, no `common`),
                       STDIO ⇄ Claude Desktop
```

`common` provides:
- DTOs (`ChatRequest`, `ChatResponse`, …)
- `observability/` — `ObservabilityAutoConfiguration` (actuator filter + histogram
  buckets), `Lc4jGenAiMetricsAutoConfiguration` + `GenAiMetricsChatModelListener`
  (GenAI metrics & spans for LangChain4j, meters pre-registered at 0),
  `OtelLoggingBridgeAutoConfiguration` (Logback → OTLP), `TracingToolProvider`,
  `McpToolTelemetry`; all registered via
  `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- `approval/` — `HumanApprovalService` (generic `gate(...)`), `PendingApproval`,
  `ApprovalEndpoints`; wired explicitly by consumers, never auto-configured
- `milan/` — `MilanKnowledgeBase` (shared domain data for both patterns modules)
- `languages/` — `TranslationLanguages` (chaining target languages + `Mixed`)

Per-module dependencies (managed by parent BOMs — Spring Boot 4.0.4,
LangChain4j 1.16.3, Spring AI 2.0.0, Java 21):

| Module | Key dependencies |
|--------|------------------|
| common | spring-context, jackson-annotations, jakarta.validation, opentelemetry-logback-appender; optional: spring-boot-autoconfigure, spring-web, micrometer-observation, micrometer-core, micrometer-tracing, opentelemetry-api, langchain4j, langchain4j-core; provided: jakarta.servlet-api |
| raw-agent | starter-web (`java.net.http`, Jackson 3 — no AI framework) |
| langchain4j-agent-local | langchain4j + `langchain4j-spring-boot4-starter`, ollama / open-ai boot4 starters |
| langchain4j-agent-mcp | + langchain4j-mcp (`StreamableHttpMcpTransport`) |
| spring-ai-agent-local | spring-ai ollama + openai starters (provider chosen per profile) |
| spring-ai-agent-mcp | + spring-ai MCP client starter (`streamable-http` connections in yml) |
| patterns-langchain4j | langchain4j + agentic (DSL: sequence/loop builders) |
| patterns-spring-ai | spring-ai starters (no workflow API — patterns in plain Java) |
| mcp-server | `spring-ai-starter-mcp-server-webmvc` (`protocol: STREAMABLE`), virtual threads |
| claude-mcp-server | `spring-boot-starter-parent` (own), spring-ai MCP server starter in STDIO mode, no web |
| all reactor modules | starter-actuator, springdoc, **spring-boot-starter-opentelemetry** (traces + logs + OTLP metrics), micrometer-registry-prometheus (from parent) |
