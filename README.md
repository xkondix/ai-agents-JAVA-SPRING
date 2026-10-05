# ai-agents-JAVA-SPRING

# 🤖 Multi-Agent Systems with Spring AI, LangChain4j & MCP

## This project shows how to build **AI agents in Java** three ways — by hand, with `LangChain4j` and with `Spring AI` — and then how to prove they work: MCP servers as a process boundary, the five agentic workflow patterns, multimodal generation, and full OpenTelemetry observability on a Grafana LGTM stack. Continuation of the "LangChain4j with Spring Boot" presentation series. Slides live in the `presentations` folder.

---

## 🔥 Features

- 🔁 **The same agent, three times**: a hand-written loop, LangChain4j `AiServices`, Spring AI `ChatClient` — the loop is visible once and hidden twice
- 🔌 **MCP over Streamable HTTP**: tools in another process, plus a STDIO server wired into Claude Desktop
- 🧩 **All four MCP primitives**: tools, resources, prompts and completions — not just tools
- 🧠 **Five workflow patterns** (chaining, routing, parallelization, evaluator-optimizer, orchestrator-workers), each implemented twice for comparison
- 🎨 **Multimodal**: image, speech, music and video — one router, five tools, four different provider protocols
- 🙋 **Human-in-the-loop**: a tool call blocks until a person approves it, and you watch the span grow in Tempo
- 📊 **Three signals, three instrumentations**: hand-written, listener-based and automatic — all on one dashboard
- 🕵️ **A catalogue of silent failures**: twenty ways this stack breaks without logging anything

---

## 🚀 Quick Start

```
docker compose up -d          # Grafana LGTM + Redis + Ollama
setx OPENROUTER_API_KEY "sk-or-..."   # Windows; reopen the shell afterwards
mvn clean install -DskipTests
```

Start `mcp-server` (8081) **first** — both MCP clients connect eagerly, so a
missing server stops them from starting rather than degrading them. Then the
agents, then the UI:

```
cd mcp-server && mvn spring-boot:run
cd chat-ui && npm install && npm run dev
```

`claude-mcp-server` is built separately and launched by Claude Desktop:

```
cd claude-mcp-server && mvn clean package
```

---

## 🧩 Modules

| Module | Port | What it shows |
|---|---|---|
| `raw-agent` | 8090 | The agent loop with no framework at all |
| `langchain4j-agent-local` | 8082 | Raw loop + `AiServices` side by side |
| `langchain4j-agent-mcp` | 8083 | MCP orchestrator, **no** trace propagation |
| `spring-ai-agent-local` | 8084 | `ChatClient` + Advisors + `@Tool` |
| `spring-ai-agent-mcp` | 8085 | MCP orchestrator **with** W3C trace propagation |
| `patterns-langchain4j` | 8087 | Five patterns, Agentic DSL where it pays off |
| `patterns-spring-ai` | 8088 | Five patterns, plain Java |
| `multimodal-lab` | 8089 | Image / speech / music / video, Spring AI only |
| `mcp-server` | 8081 | MCP tools server + Approval API |
| `claude-mcp-server` | STDIO | Project file access for Claude Desktop |
| `chat-ui` | 3000 | Chat, `/approvals`, `/patterns`, `/multimodal` |

Infrastructure: Grafana 3100 · Prometheus 9090 · Tempo 3201 · OTLP 4318 · Redis 6379 · Ollama 11434.

---

## 🎭 Two MCP clients, on purpose

`spring-ai-agent-mcp` propagates the W3C trace context into MCP calls and
`langchain4j-agent-mcp` deliberately does not. The same tool call produces
**one trace with two services** in one module and **two unrelated traces** in
the other.

Same protocol, same transport, same tool — the difference is a client
implementation decision, not a property of MCP. That contrast is the single
best demo in the repo.

---

## Tech Stack

- **Java 21** · **Spring Boot 4.0.4** · **Spring Framework 7.0.6**
- **Spring AI 2.0.1**
- **LangChain4j 1.16.3** (`-spring-boot4-` starters + Agentic DSL)
- **MCP** over Streamable HTTP and STDIO
- **Jackson 3** (`tools.jackson`)
- **OpenTelemetry** via `spring-boot-starter-opentelemetry`
- **Grafana LGTM** 0.32.0: Tempo, Loki, Prometheus, Pyroscope
- **Redis**: chat memory + artifact gallery
- **React 18 + Vite + Tailwind**
- **OpenRouter** (default) or **Ollama** (local profile)

---

## ⚠️ Gotchas worth knowing before you clone

- **`/v1` belongs in the base-url for everyone** since Spring AI 2.0 — the
  OpenAI module was rewritten on the official SDK and appends only
  `/chat/completions`. Getting it wrong gives `404: Unknown`, which says
  nothing about URLs.
- **The catalogue is the source of truth, not the documentation.** OpenRouter's
  own TTS docs use a model slug that its catalogue does not serve.
- **`ChatClient.builder(chatModel)` hands you `ObservationRegistry.NOOP`** and
  silently removes every span and metric below the call. Always inject
  `ChatClient.Builder`.
- **`@McpToolParam` has no `name` attribute** — parameter names come from the
  `-parameters` compiler flag and nothing else. Without it every argument
  arrives as `null`, with no error anywhere.
- **Actuator is required for OTLP log export**, even in a module with no web
  server. Drop it and logs back off in silence while traces and metrics work.
- **Spring AI 2.0.1 caps tool calls per turn** — 40 per tool, 150 in total
  (`spring.ai.tools.limits.*`); 2.0.0 had no limit at all. The cap counts
  calls, not failures, so it does not replace a retry budget for paid tools.

Full list: [`OBSERVABILITY.md`](OBSERVABILITY.md) §3.

---

## 📚 Documentation

- [`ENDPOINTS.md`](ENDPOINTS.md) — every endpoint, tool, meter and dependency
- [`PATTERNS.md`](PATTERNS.md) — the five workflow patterns, with diagrams
- [`OBSERVABILITY.md`](OBSERVABILITY.md) — three signals, and the failures that stay silent
- [`claude-mcp-server/README.md`](claude-mcp-server/README.md) — STDIO, Claude Desktop, sandbox
- [`chat-ui/README.md`](chat-ui/README.md) — the four pages and what they demo
- `presentations/` — slides for the talk series

---

## Useful pages

**Documentation**

- <https://docs.spring.io/spring-ai/reference/> `(Spring AI)`
- <https://docs.spring.io/spring-ai/reference/upgrade-notes.html> `(Spring AI upgrade notes — what changed in 2.0.0 and 2.0.1)`
- <https://docs.langchain4j.dev/> `(LangChain4j)`
- <https://modelcontextprotocol.io/> `(MCP specification)`
- <https://opentelemetry.io/docs/specs/semconv/gen-ai/> `(GenAI semantic conventions)`

**Github**

- <https://github.com/spring-projects/spring-ai/> `(Spring AI source — the only reliable answer to "does this property exist")`
- <https://github.com/langchain4j/langchain4j-examples/> `(LangChain4j examples)`
- <https://github.com/grafana/docker-otel-lgtm/> `(the observability stack in one container)`

**Reading**

- <https://www.anthropic.com/engineering/building-effective-agents> `(the five patterns)`
- <https://github.com/xkondix/aiSandbox-JAVA-SPIRNG> `(part one of the series: RAG, chat memory, tools)`
