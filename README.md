# ai-agents-JAVA-SPRING

# 🤖 Multiagent Systems with Spring AI, LangChain4j & MCP

## This project shows how to build **AI agents in Java** three ways (by hand, with `LangChain4j` and with `Spring AI`) and then how to prove they work, with MCP servers as a process boundary, the five agentic workflow patterns, multimodal generation, and full OpenTelemetry observability on a Grafana LGTM stack. It continues the "LangChain4j with Spring Boot" presentation series. Slides live in the `presentations` folder.

***

## 🔥 Features

* 🔁 **The same agent, three times**, as a handwritten loop, LangChain4j `AiServices` and Spring AI `ChatClient`. The loop is visible once and hidden twice.
* 🔌 **MCP over Streamable HTTP** puts tools in another process, and a STDIO server is wired into Claude Desktop.
* 🧩 **All four MCP primitives** are covered (tools, resources, prompts and completions), not just tools.
* 🧠 **Five workflow patterns** (chaining, routing, parallelization, evaluator optimizer, orchestrator workers), each implemented twice for comparison.
* 🎨 **Multimodal** generation of image, speech, music and video, with one router, five tools and four different provider protocols.
* 🙋 **Human in the loop**, where a tool call blocks until a person approves it and you watch the span grow in Tempo.
* 📊 **Three signals, three instrumentations** (handwritten, based on a listener, and automatic), all on one dashboard.
* 🕵️ **A catalogue of silent failures** with twenty ways this stack breaks without logging anything.

***

## 🚀 Quick Start

You need your own [OpenRouter](https://openrouter.ai) API key.

```
docker compose up -d                       # Grafana LGTM + Redis + Ollama
setx OPENROUTER_API_KEY "sk-or-..."        # Windows; reopen the shell afterwards
export OPENROUTER_API_KEY="sk-or-..."      # macOS / Linux
mvn clean install -DskipTests
```

Start `mcp-server` (8081) **first**. Both MCP clients connect eagerly, so a
missing server stops them from starting rather than degrading them. Then start
the agents, then the UI.

```
cd mcp-server && mvn spring-boot:run
cd chat-ui && npm install && npm run dev
```

`claude-mcp-server` is built separately and launched by Claude Desktop.

```
cd claude-mcp-server && mvn clean package
```

***

## 🧩 Modules

| Module | Port | What it shows |
|---|---|---|
| `raw-agent` | 8090 | The agent loop with no framework at all |
| `langchain4j-agent-local` | 8082 | Raw loop + `AiServices` side by side |
| `langchain4j-agent-mcp` | 8083 | MCP orchestrator, trace propagation behind a switch (off by default) |
| `spring-ai-agent-local` | 8084 | `ChatClient` + Advisors + `@Tool` |
| `spring-ai-agent-mcp` | 8085 | MCP orchestrator **with** W3C trace propagation |
| `patterns-langchain4j` | 8087 | Five patterns, Agentic DSL where it pays off |
| `patterns-spring-ai` | 8088 | Five patterns, plain Java |
| `multimodal-lab` | 8089 | Image, speech, music and video, Spring AI only |
| `mcp-server` | 8081 | MCP tools server + Approval API |
| `claude-mcp-server` | STDIO | Project file access for Claude Desktop |
| `chat-ui` | 3000 | Chat, `/approvals`, `/patterns`, `/multimodal` |

Infrastructure ports are Grafana 3100, Prometheus 9090, Tempo 3201, OTLP 4318,
Redis 6379 and Ollama 11434.

***

## 🎭 Two MCP clients, on purpose

`spring-ai-agent-mcp` propagates the W3C trace context into MCP calls out of
the box. `langchain4j-agent-mcp` does not by default, because its MCP client
sends requests through the JDK `HttpClient`, which Spring Boot does not
instrument. The same tool call produces **one trace with two services** in one
module and **two unrelated traces** in the other.

Set `lc4j.mcp.trace-propagation.enabled=true` and `McpClientConfig` adds the
`traceparent` header itself, so the LangChain4j module shows one trace too.

Same protocol, same transport, same tool. The difference is a client
implementation decision, not a property of MCP. That contrast is the single
best demo in the repo.

***

## Tech Stack

* **Java 21**, **Spring Boot 4.0.4** and **Spring Framework 7.0.6**
* **Spring AI 2.0.1**
* **LangChain4j 1.16.3** (`-spring-boot4-` starters + Agentic DSL)
* **MCP** over Streamable HTTP and STDIO
* **Jackson 3** (`tools.jackson`)
* **OpenTelemetry** via `spring-boot-starter-opentelemetry`
* **Grafana LGTM** 0.32.0 with Tempo, Loki, Prometheus and Pyroscope
* **Redis** for chat memory and the artifact gallery
* **React 18 + Vite + Tailwind**
* **OpenRouter** (default) or **Ollama** (local profile)

***

## ⚠️ Gotchas worth knowing before you clone

* **`/v1` belongs in the `base-url` for everyone** since Spring AI 2.0. The
  OpenAI module was rewritten on the official SDK and appends only
  `/chat/completions`. Getting it wrong gives `404: Unknown`, which says
  nothing about URLs.
* **The catalogue is the source of truth, not the documentation.** OpenRouter's
  own TTS docs use a model slug that its catalogue does not serve.
* **`ChatClient.builder(chatModel)` hands you `ObservationRegistry.NOOP`** and
  silently removes every span and metric below the call. Always inject
  `ChatClient.Builder`.
* **`@McpToolParam` has no `name` attribute.** Parameter names come from the
  `-parameters` compiler flag and nothing else. Without it every argument
  arrives as `null`, with no error anywhere.
* **Actuator is required for OTLP log export**, even in a module with no web
  server. Drop it and logs back off in silence while traces and metrics work.
* **Spring AI 2.0.1 caps tool calls per turn** at 40 per tool and 150 in total
  (`spring.ai.tools.limits.*`). Version 2.0.0 had no limit at all. The cap
  counts calls, not failures, so it does not replace a retry budget for paid
  tools.

The full list is in [`OBSERVABILITY.md`](OBSERVABILITY.md) §3.

***

## 📚 Documentation

* [`ENDPOINTS.md`](ENDPOINTS.md) lists every endpoint, tool, meter and dependency
* [`PATTERNS.md`](PATTERNS.md) explains the five workflow patterns, with diagrams
* [`OBSERVABILITY.md`](OBSERVABILITY.md) covers the three signals and the failures that stay silent
* [`claude-mcp-server/README.md`](claude-mcp-server/README.md) covers STDIO, Claude Desktop and the sandbox
* [`chat-ui/README.md`](chat-ui/README.md) describes the four pages and what they demo
* `presentations/` holds the slides for the talk series

***

## Useful pages

**Documentation**

* [Spring AI reference](https://docs.spring.io/spring-ai/reference/)
* [Spring AI upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html), what changed in 2.0.0 and 2.0.1
* [LangChain4j](https://docs.langchain4j.dev/)
* [MCP specification](https://modelcontextprotocol.io/)
* [GenAI semantic conventions](https://opentelemetry.io/docs/specs/semconv/gen-ai/)

**GitHub**

* [Spring AI source](https://github.com/spring-projects/spring-ai/), the only reliable answer to "does this property exist"
* [LangChain4j examples](https://github.com/langchain4j/langchain4j-examples/)
* [Grafana LGTM image](https://github.com/grafana/docker-otel-lgtm/), the observability stack in one container

**Reading**

* [Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents), the five patterns
* [aiSandbox-JAVA-SPIRNG](https://github.com/xkondix/aiSandbox-JAVA-SPIRNG), part one of the series (RAG, chat memory, tools)

***

## About the images

The illustrations in the slides are generated by AI and meant as humour. All
trademarks that appear in them belong to their respective owners. This is a
noncommercial, educational project, not affiliated with or endorsed by any of
them.
