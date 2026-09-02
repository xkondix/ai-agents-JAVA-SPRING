# Observability — how it is wired and where it breaks silently

One document for what eight `application.yml` files used to repeat in
comments. Each module keeps a two-line pointer here.

## 1. Three signals, one port

Every reactor module sends all three signals to the Grafana LGTM container on
`localhost:4318` (OTLP/HTTP). Spring Boot 4 property layout:

| Signal | Property | Owner |
|--------|----------|-------|
| traces | `management.opentelemetry.tracing.export.otlp.endpoint` = `…/v1/traces` | OpenTelemetry SDK (Boot auto-config) |
| logs | `management.opentelemetry.logging.export.otlp.endpoint` = `…/v1/logs` | OpenTelemetry SDK + **our** Logback bridge |
| metrics | `management.otlp.metrics.export.url` = `…/v1/metrics` | Micrometer `OtlpMeterRegistry` (push, 60 s) |

The Boot 3 spelling `management.otlp.tracing.endpoint` is **dead** on Boot 4.
It is not kept "just in case": an unknown property binds to nothing, and two
spellings side by side only guarantee that a future reader cannot tell which
one is live. Symptom of the stale one: metrics arrive, Tempo stays empty.

All of it comes from one dependency in the parent pom:
`spring-boot-starter-opentelemetry` (OTel API + SDK auto-config, Micrometer
tracing bridge, OTLP exporters for traces/logs, `micrometer-registry-otlp`).
Actuator stays for its endpoints; Boot 4.0.0 additionally needed it for log
export, fixed in 4.0.1.

### Logs need two things Boot does not give you
1. `common/logback-spring.xml` — declares the `OpenTelemetryAppender` (and
   keeps `io.opentelemetry`, `io.micrometer`, LangChain4j raw HTTP and the
   `MCP` ping logger console-only, so telemetry never reports on itself).
2. `OtelLoggingBridgeAutoConfiguration` — calls `OpenTelemetryAppender.install(openTelemetry)`
   once the bean exists. Until then the appender buffers 1000 records and
   drops the rest, with no error anywhere.

Miss either and Loki stays empty while "Logs for this span" opens a perfectly
correct query that returns nothing.

## 2. Three instrumentation approaches

| | raw-agent | LangChain4j modules | Spring AI modules |
|---|---|---|---|
| chat span | `Tracer.nextSpan()` by hand in `LlmClient` | `GenAiMetricsChatModelListener` (`common`) | automatic |
| tool span | by hand in `RawAgentLoop` | `TracingToolProvider` (`common`) | automatic, `execute_tool <n>` |
| metrics | by hand in `LlmClient` | same listener | automatic (`ObservationHandler`) |
| content | — (console only) | on the span: `xkondix.observability.genai.include-prompt/completion` | in logs: `spring.ai.chat.observations.log-prompt/completion` |
| depth of a trace | 2 levels | 3 levels | 7 levels (`chat_client → tool_calling → advisor → call → chat → POST`) |

Meter names are identical on purpose so the three land on the same panels:
`gen.ai.client.token.usage` (counter), `gen.ai.client.operation` (timer),
`gen.ai.client.tool.requests` (counter). Spring AI spells the timer
`gen_ai.client.operation`; Prometheus normalises both to
`gen_ai_client_operation` — but Micrometer-level matching (a `MeterFilter`)
must list both.

MCP servers add the server side: `McpToolTelemetry` (`common`, used by
mcp-server) and its hand copy in `claude-mcp-server/CodeToolsService` emit
`mcp_tool <n>` SERVER spans and `mcp.tool.{calls,duration,payload.size}`.

## 3. The silent failures collected so far

Every item below produced **no error in any log**. That is the thesis of
the project, and the list is the material.

| # | What | Symptom | Where it is handled |
|---|------|---------|---------------------|
| 1 | Boot 3 property spelling on Boot 4 | metrics fine, Tempo empty | all `application.yml` |
| 2 | Logback appender declared but not `install()`ed | Loki empty | `OtelLoggingBridgeAutoConfiguration` |
| 3 | `@ConditionalOnBean(MeterRegistry)` evaluated before the registry exists; `afterName` pointing at a class that moved in Boot 4 | no LC4j spans, no LC4j metrics | `Lc4jGenAiMetricsAutoConfiguration` (ObjectProvider) |
| 4 | `MeterRegistry` fallback to `SimpleMeterRegistry` | metrics exist in-process, nothing exported | WARN log in both places |
| 5 | `spring.ai.chat.observations.include-prompt` renamed to `log-prompt` in 1.0.0-M8 | key ignored; content moved from span to log | Spring AI ymls |
| 6 | `ChatClient.builder(chatModel)` = `ObservationRegistry.NOOP` | no ChatClient/tool spans, no `spring_ai_tool_*` | all ChatClients inject `ChatClient.Builder` |
| 7 | `$__rate_interval` 60 s against a 60 s push | rate panels empty at "Last 1 hour", full at "Last 7 days" | `interval: 1m` on every query + datasource `timeInterval: 60s` |
| 8 | Counter created on first use, born non-zero | first request after restart invisible to `rate()`, $0 on cost | meters pre-registered at 0 (raw, LC4j); warm-up request for Spring AI |
| 9 | Timer without buckets → only `le="+Inf"` | p95 panel empty, avg fine | `MeterFilter` in `ObservabilityAutoConfiguration` |
| 10 | Same meter, two spellings (`gen.ai.` vs `gen_ai.`) | filter fixed 4 of 7 modules | both prefixes listed |
| 11 | `*_max` not materialised over OTLP → Prometheus | human-in-the-loop panel empty | panels use sum/count |
| 12 | Jackson 3 `FAIL_ON_NULL_FOR_PRIMITIVES` default | 500 on `/patterns/parallel`, LC4j twin fine | `Double` + normalisation in `ParallelizationPattern` |
| 13 | `allow-bean-definition-overriding: true` | our tool bean replaced, model got zero tools | flag removed |
| 14 | MCP transport mismatch | agent fails to start (loud, but reads like a network error) | `McpClientConfig` comments |

## 4. Grafana conventions

- Dashboard and datasources are **provisioned from the repo**
  (`grafana/provisioning/`); dashboards reload every 30 s, datasources only
  on container recreate. The LGTM container has a volume on `/data`, so a
  recreate keeps history.
- Group by `job` (service.name), never by `framework` — Spring AI series
  carry no `framework` tag.
- `palette-classic-by-name` on every timeseries: a module keeps its colour on
  every panel.
- Preflight row first: seconds since last push per module, modules pushing,
  modules that reported LLM metrics (`max_over_time`, not `increase`, because
  of #8), failed LLM calls.
- Traces row is a **table** on a TraceQL search; the Traces panel type renders
  one trace only and shows "No data found in response" for a list.

## 5. Pre-demo checklist

1. `docker compose up -d` (LGTM + Ollama; `--profile extras` only if needed).
2. Start `mcp-server` first, then the two `*-agent-mcp` modules, then the rest.
3. Grafana opens on the dashboard: Preflight must read **9 / 7 / 0** after step 4.
4. One warm-up request to every agent (Spring AI meters are not pre-registered).
5. Open the Traces row: every module has a trace; `spring-ai-agent-mcp` shows
   Services: 2.
6. Click "Logs for this span" on one chat span ~10 s after the request — the
   log export is batched.
