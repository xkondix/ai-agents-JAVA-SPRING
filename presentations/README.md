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

### One tool span name in every trace

Every trace screenshot (**Observability**, **Traces**, **Advisors Inside the
Loop**, **Default**, **Memory in the Loop**) shows the tool as
`execute_tool <name>` with `gen_ai.operation.name=execute_tool`. Spring AI 2.0
emits that span itself. `raw-agent` and the LangChain4j modules create the
same span by hand (`RawAgentLoop`, `RawAgentLoopService`, and
`TracingToolProvider` in `common`), so a tool call looks the same in all three.

### Which hooks the repo actually uses

Of the five on **Five ways around one loop**, two are wired in:
`ChatModelListener` (`GenAiMetricsChatModelListener`) and the `ToolProvider`
wrapper (`TracingToolProvider`), both in `common`.

The code on **Two callbacks instead of a wrapper** is an illustration and is
not in any module. The reason is the one on the slide: with the default
`ToolArgumentsErrorHandler`, which rethrows, a tool call whose arguments don't
parse never reaches `afterToolExecution` (LangChain4j 1.16.3, `ToolService`),
so a span opened in `beforeToolExecution` would never be closed. The wrapper
opens and closes the span in one `try`/`finally`.

### One memory advisor, except on one slide

**Default** and **Memory in the Loop** use a single `MessageChatMemoryAdvisor`,
outside and inside the loop. **Memory Inside && Outside** shows a bug from an
earlier version of this repo: `SpringAiConfig` also registered one through
`defaultAdvisors(...)`, so every request ran with two advisors on the same
conversation id and the question was saved twice. To reproduce it, inject
`MessageWindowChatMemory chatMemory` into the `ChatClient` bean in
`SpringAiConfig`, add
`.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())` and
call `/api/v1/agent/chat/memory-in-loop`.

### Versions

Spring Boot 4.0.4 · Spring AI 2.0.1 · LangChain4j 1.16.3 · Java 21.
The tool call limits on the **Who stops the loop?** slide (40 per tool, 150 per
turn) exist only from Spring AI 2.0.1 — 2.0.0 had no limit at all
([upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#_new_tool_call_limits)).
