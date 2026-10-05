# Presentations

Slides for a four-part talk series built on this repository. It continues
last year's talk on the basics (RAG, chat memory, tools), whose code lives in
[aiSandbox-JAVA-SPIRNG](https://github.com/xkondix/aiSandbox-JAVA-SPIRNG).

## The series

| # | Title | What it covers | Modules | Status |
|---|-------|----------------|---------|--------|
| I | **The Agent Is a Loop** | Writing one by hand, and what frameworks hide when they write it for you | `raw-agent`, `langchain4j-agent-local`, `spring-ai-agent-local`, `multimodal-lab` | ✅ [PDF](The%20Agent%20Is%20a%20Loop%20.pdf) · [PPTX](The%20Agent%20Is%20a%20Loop%20.pptx) |
| II | **Agentic Workflow Patterns** | Five shapes, two frameworks, and when the DSL earns its keep | `patterns-langchain4j`, `patterns-spring-ai` — see [`PATTERNS.md`](../PATTERNS.md) | planned |
| III | **MCP as a Process Boundary** | Tools, resources and prompts over two transports: HTTP for agents, STDIO for Claude Desktop | `mcp-server`, `claude-mcp-server`, `langchain4j-agent-mcp`, `spring-ai-agent-mcp` | planned |
| IV | **How Do You Know It Works?** | Traces, metrics, logs and dashboards for everything your agents actually do | Grafana LGTM stack — see [`OBSERVABILITY.md`](../OBSERVABILITY.md) | planned |

Endpoints, tools and meters for every module: [`ENDPOINTS.md`](../ENDPOINTS.md).

## Notes for Part I

Read these if you go through the PDF without the talk.

### Some screenshots predate the span rename

The screenshots on the **Observability**, **Traces**, **Default** and
**Memory in the Loop** slides were taken before the project moved to
Spring AI 2.0.1. In them the hand-written tool span in `raw-agent` and the
LangChain4j modules is still called `tool_call <name>`.

The code now emits `execute_tool <name>` with
`gen_ai.operation.name=execute_tool` — the same name Spring AI has used since
2.0 (it renamed its own span from `tool_call`). Only the name changed: the
shape of every trace (`chat → tool → chat`) is exactly what the slides show,
which is why the screenshots were kept.

### Two memory advisors on purpose

On the **Default** and **Memory in the Loop** slides every chain contains two
`message_chat_memory` spans. That is deliberate: `spring-ai-agent-local`
registers one `MessageChatMemoryAdvisor` as a default on the `ChatClient` and
adds another per request — the setup the Spring AI documentation advises
against — to show its effect. With both outside the loop the first turn looks
normal; with one moved inside the loop, the second iteration's prompt carries
the user question twice. Remove the default advisor in `SpringAiConfig` to get
the documented behaviour.

### Versions

Spring Boot 4.0.4 · Spring AI 2.0.1 · LangChain4j 1.16.3 · Java 21.
The tool call limits on the **Who stops the loop?** slide (40 per tool, 150 per
turn) exist only from Spring AI 2.0.1 — 2.0.0 had no limit at all
([upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#_new_tool_call_limits)).
