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
| GET | `/api/v1/patterns/evaluator?season=` | Evaluator-optimizer loop (exit at score ≥ 0.85, max 4 iterations) |
| POST | `/api/v1/patterns/orchestrator` | Orchestrator-workers — body is the task |

### multimodal-lab (port 8089)
One router agent, five tools, four provider protocols. **Spring AI only** —
this is the module where the framework comparison stops, because LangChain4j
has no equivalent audio story. Details: `multimodal-lab/` class comments.

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/v1/multimodal` | Multipart: `question`, `image`, `audio`, `conversationId`. Returns the answer plus the URLs of everything produced |
| GET | `/api/v1/multimodal/artifacts?conversationId=&scope=` | Gallery. `scope=all` (default) merges the Redis index with a disk scan; `scope=conversation` narrows to one thread |
| GET | `/api/v1/multimodal/media/**` | Serves stored artifacts, `Content-Disposition: inline` |
| DELETE | `/api/v1/multimodal/memory/{conversationId}` | Clears the model's memory. Files and gallery are kept |
| DELETE | `/api/v1/multimodal/artifacts/{conversationId}` | Clears the gallery index only. Files stay on disk |

Two separate resets on purpose: wiping what the model remembers and throwing
away what it made are different intentions.

> Voice input (`audio`) is implemented end to end but **switched off in the
> UI**: the OpenRouter transcription slug was never confirmed and the endpoint
> answered 500. The backend path stays because the reason transcription cannot
> be a tool — a voice note *is* the question, so the model would have to know
> its contents to decide to read it — is one of the clearer points the module
> makes.

### Approval REST API
Same contract everywhere, so the Chat UI talks to all sources the same way.

| Method | Path | Available on |
|--------|------|--------------|
| GET | `/approvals` | mcp-server (8081), patterns-langchain4j (8087), patterns-spring-ai (8088) |
| POST | `/approvals/{id}/approve` | as above — unblocks the waiting tool call |
| POST | `/approvals/{id}/reject` | as above — the tool returns a refusal to the model |

> `claude-mcp-server` has **no** approval flow by design: over STDIO there is no
> second channel for a human decision, and blocking the JSON-RPC thread would
> deadlock the stream. See `claude-mcp-server/README.md`.
> `multimodal-lab` has none either — nothing it does is destructive; its
> guardrails are a feature flag and a retry budget, not a human gate.

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

### multimodal-lab — one namespace, four protocols
The router sees a flat list of tools and has no idea they come from three
beans or that each speaks a different API shape. That asymmetry is the point
of the module.

| Tool | Model | Protocol | Cost |
|------|-------|----------|------|
| `analyze_image` | `gpt-4o` (own setting) | `/chat/completions` via Spring AI | cents |
| `generate_image` | `gpt-image-1` | `/images/generations` via Spring AI | cents |
| `speak_text` | `x-ai/grok-voice-tts-1.0` | `/audio/speech` via Spring AI | per character |
| `generate_music` | Lyria 3 pro / clip | streaming `/chat/completions`, **raw HTTP** | $0.08 / $0.04 flat |
| `generate_video` | `google/veo-3.1` | job queue `/videos`, **raw HTTP** | ~$1.60 per 4 s |

`generate_video` is behind `multimodal.video.enabled` and **off by default**;
when the bean is absent the router's system prompt does not mention video at
all, because a tool the model can see but cannot use gets offered to the user
and then fails.

All five check a shared **retry budget** (`MediaCollector`, 2 failures per
request) before doing any work. `ToolCallingAdvisor` has no failure semantics —
a tool answering `ERROR:` has, as far as the loop is concerned, answered — and
one request once produced over a hundred calls to a single failing tool.

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
| patterns-spring-ai | + hand-written `evaluator_iteration N` spans carrying `evaluator.score` | — |
| mcp-server | `mcp_tool <n>` (SERVER kind, `McpToolTelemetry`) | `mcp_tool_calls_total`, `mcp_tool_duration_milliseconds_*`, `mcp_tool_payload_size_chars_*` |
| claude-mcp-server | same, own copy in `CodeToolsService` | same names, `framework=spring-ai` |
| multimodal-lab | automatic for the three Spring AI tools; **none** for music and video | `mm_generation_*` for all five — the only accounting for the two that bypass ChatClient |

**Spring AI observations exist only with the auto-configured `ChatClient.Builder`.**
`ChatClient.builder(chatModel)` hard-codes `ObservationRegistry.NOOP` and
silently drops every ChatClient-level observation, including tool spans and
`spring_ai_tool_*` — all ChatClients in the project inject the builder.

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
- `mm_generation_calls_total`, `mm_generation_duration_seconds_*`,
  `mm_generation_size_chars_*` — multimodal-lab, tag `modality`
  (`image`, `speech`, `music`, `video`, `vision`)
- `mm_generation_cost_usd_*` — **video only**, and the only metric in the
  project carrying a real invoice figure rather than an estimate; OpenRouter
  returns it in `usage.cost` on the job poll
- `http_server_requests_milliseconds_*`, `jvm_*`

No `*_max` series reaches Prometheus over OTLP; panels use sum/count.

**Tokens stop describing cost in multimodal-lab.** An image bills per picture,
speech per character, a Lyria song at a flat rate, video per generated second —
and music and video do not go through `ChatClient` at all, so they produce no
`gen_ai` span and no token counters. The modality that costs the most is the
one standard instrumentation never sees.

### Tracing notes
- GenAI Semantic Conventions on each LLM span: `gen_ai.request.model`,
  `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`,
  `gen_ai.response.finish_reasons`, `gen_ai.system`.
- **Prompt/completion content**: Spring AI logs it (`spring.ai.chat.observations.log-prompt`
  / `log-completion` → Loki, trace-correlated); LangChain4j puts it on the span
  (`xkondix.observability.genai.include-prompt` / `include-completion` → Tempo).
  Same data, two signals — deliberate contrast.
  **Not enabled in multimodal-lab**: an attached image would be base64 in a span
  attribute, and Tempo drops oversized attributes silently.
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

> **Not yet built:** a row for `mm_generation_*`. The metrics are pushed and
> nothing draws them, `mm_generation_cost_usd` included.

### Infrastructure ports (docker-compose)

Three containers, all of them used. `docker compose up -d` starts everything.

| Service | Port | Notes |
|---------|------|-------|
| Grafana | 3100 | admin/admin |
| OTLP gRPC / HTTP | 4317 / 4318 | Telemetry ingest |
| Prometheus | 9090 | Metrics UI |
| Tempo | 3201 | Trace store API |
| Redis | 6379 | **Used by multimodal-lab** — chat memory and the gallery index |
| Ollama | 11434 | Local LLM API (`local` Spring profile only) |

> Chroma, Jira and its Postgres used to sit behind a `--profile extras`,
> reserved for RAG and integration demos that were never built. Removed —
> reserved infrastructure is a maintenance surface, not a plan. Redis moved
> out of that profile into the default at the same time, because it stopped
> being reserved: `multimodal-lab` uses it, and while it sat behind the
> profile a plain `docker compose up` left it down and the module logged
> "Unable to connect to Redis" on every request.

> On Windows use `127.0.0.1`, not `localhost`, for Redis. `localhost` resolves
> to `::1` first and Docker Desktop publishes on IPv4 only, so a healthy
> container still answers "Unable to connect to Redis". The same resolution
> order shows up in the OTLP exporter's error message.

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

   patterns-langchain4j (8087)   patterns-spring-ai (8088)   multimodal-lab (8089)
              └──────────────── common ───────────────────────────┘
                                                                   │
                                                          Redis (memory + gallery)

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
| patterns-langchain4j | langchain4j + agentic (DSL: sequence / loop / conditional builders) |
| patterns-spring-ai | spring-ai starters (no workflow API — patterns in plain Java) |
| multimodal-lab | spring-ai openai starter (chat, vision, image, speech, transcription) + `spring-ai-starter-model-chat-memory-repository-redis`; music and video use `java.net.http` directly |
| mcp-server | `spring-ai-starter-mcp-server-webmvc` (`protocol: STREAMABLE`), virtual threads |
| claude-mcp-server | `spring-boot-starter-parent` (own), spring-ai MCP server starter in STDIO mode, no web |
| all reactor modules | starter-actuator, springdoc, **spring-boot-starter-opentelemetry** (traces + logs + OTLP metrics), micrometer-registry-prometheus (from parent) |
