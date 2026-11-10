# Presentations

Slides for a four-part talk series built on this repository. It continues
last year's talk on the basics (RAG, chat memory, tools), whose code lives in
[aiSandbox-JAVA-SPIRNG](https://github.com/xkondix/aiSandbox-JAVA-SPIRNG).

## The series

| # | Title | What it covers | Modules | Status |
|---|-------|----------------|---------|--------|
| I | **The Agent Is a Loop** | Writing one by hand, and what frameworks hide when they write it for you | `raw-agent`, `langchain4j-agent-local`, `spring-ai-agent-local`, `multimodal-lab` | ✅ [PDF](The%20Agent%20Is%20a%20Loop%20.pdf) · [PPTX](The%20Agent%20Is%20a%20Loop%20.pptx) |
| II | **Agentic Workflow Patterns** | Five shapes, two frameworks, and when the DSL earns its keep | `patterns-langchain4j`, `patterns-spring-ai` — see [`PATTERNS.md`](../PATTERNS.md) | ✅ [PDF](Agentic%20Workflow%20Patterns.pdf) · [PPTX](Agentic%20Workflow%20Patterns.pptx) |
| III | **MCP as a Process Boundary** | Tools, resources and prompts over two transports: HTTP for agents, STDIO for Claude Desktop | `mcp-server`, `claude-mcp-server`, `langchain4j-agent-mcp`, `spring-ai-agent-mcp` | ✅ [PDF](MCP%20as%20a%20Process%20Boundary.pdf) · [PPTX](MCP%20as%20a%20Process%20Boundary.pptx) |
| IV | **How Do You Know It Works?** | Traces, metrics, logs and dashboards for everything your agents actually do | Grafana LGTM stack — see [`OBSERVABILITY.md`](../OBSERVABILITY.md) | 🚧 in progress |

Endpoints, tools and meters for every module: [`ENDPOINTS.md`](../ENDPOINTS.md).

## About the images

The illustrations in the slides are AI-generated and meant as humour. All
trademarks that appear in them belong to their respective owners. This is a
non-commercial, educational project, not affiliated with or endorsed by any of
them.

## Sources

The number after a slide title is its PDF page number. Links last checked on
2026-11-10.

The online references describe the latest releases, which are newer than this
repo: the Spring AI reference is for 2.0.2 and the LangChain4j javadoc for a
1.23 snapshot, while the code here runs on Spring AI 2.0.1 and LangChain4j
1.16.3.

### Where the definitions come from

[Building Effective Agents](https://www.anthropic.com/engineering/building-effective-agents)
(Anthropic, December 2024) is the source of the vocabulary in Parts I and II:

- The sentence on **The true Agent** (Part I, 7) is a quote from it. The
  original reads: "They are typically just LLMs using tools based on
  environmental feedback in a loop."
- The split on **Workflow or agent?** (Part II, 3) is the article's own:
  workflows are "orchestrated through predefined code paths", agents
  "dynamically direct their own processes and tool usage".
- The line on **Climb only when you must** (Part II, 4) paraphrases its
  advice to find "the simplest solution possible, and only increasing
  complexity when needed".
- The five patterns of Part II and their names (prompt chaining, routing,
  parallelization, orchestrator-workers, evaluator-optimizer) are the five
  workflows it describes.

### Linked from the slides

- **Raw Agent** (Part I, 10):
  [OpenAI Cookbook, function calls with reasoning models](https://developers.openai.com/cookbook/examples/reasoning_function_calls)
  and
  [Strands Agents, Migrate from OpenAI](https://strandsagents.com/docs/user-guide/migrate/openai/).
- **Who stops the loop?** (Part I, 39):
  [Spring AI upgrade notes, tool call limits](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#_new_tool_call_limits).
- **DSL… but why?** (Part II, 41):
  [LangChain4j, Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/).

### Documentation behind the framework claims

Spring AI:

- [Advisors](https://docs.spring.io/spring-ai/reference/api/advisors.html):
  the order rule on **Advisor Order** (Part I, 33). Its first sentence is the
  reference's own: "The execution order of advisors in the chain is determined
  by the `getOrder()` method."
- [ToolCallingAdvisor](https://docs.spring.io/spring-ai/reference/api/tools/tool-calling-advisor.html)
  and
  [Tool Calling in Spring AI 2.0](https://spring.io/blog/2026/06/15/spring-ai-composable-tool-calling/):
  `HIGHEST_PRECEDENCE + 300` for the tool loop, `+ 200` for memory, and what
  memory stores inside and outside the loop (Part I, 32–37).
- [Tool Calling, Tool Call Limits](https://docs.spring.io/spring-ai/reference/api/tools.html#tool-call-limits):
  40 calls per tool and 150 per turn, and the "dedicated finish reason" on
  **Who stops the loop?** (Part I, 39). The manager throws
  `ToolCallLimitExceededException`; `ToolCallingAdvisor` catches it and returns
  it as the final response.
- [Provider-Native Structured Output](https://docs.spring.io/spring-ai/reference/api/structured-output/native.html):
  `useProviderStructuredOutput()`, the opt-in named in the notes for Part II.
- [Upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html):
  the 2.0 and 2.0.1 changes the slides refer to. One of them is behind the
  `@Bean …RequestCustomizer` row on **Where it breaks?** (Part III, 52):
  request customizer beans "are no longer applied by the transport
  auto-configurations".
- [MCP Client Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-client-boot-starter-docs.html):
  the connection properties, the 20 s `request-timeout` and the
  `alt_1_<name>` rename of a duplicate tool name (Part III, 31 and 50).
- [MCP Server Boot Starter](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
  and
  [Streamable-HTTP MCP Servers](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-streamable-http-server-boot-starter-docs.html):
  SSE "deprecated since 2.0.0, use STREAMABLE instead", the `/mcp` endpoint,
  and every capability enabled by default (Part III, 13, 26–27 and 52).

LangChain4j:

- [Agents and Agentic AI](https://docs.langchain4j.dev/tutorials/agents/):
  `AgenticServices`, `AgenticScope` and the sequence, conditional and loop
  builders (Part II).
- [Tools](https://docs.langchain4j.dev/tutorials/tools):
  a tool call whose arguments cannot be parsed fails the AI Service with an
  exception by default, the case behind **Two callbacks instead of a wrapper**
  (Part I, 26).
- [`AiServices.maxToolCallingRoundTrips`](https://docs.langchain4j.dev/apidocs/dev/langchain4j/service/AiServices.html#maxToolCallingRoundTrips(int)):
  "The default value is 100", and an exception when it is exceeded
  (Part I, 39).
- [Structured Outputs](https://docs.langchain4j.dev/tutorials/structured-outputs):
  format instructions appended "to the end of the `UserMessage`", and the
  `RESPONSE_FORMAT_JSON_SCHEMA` capability (Part II, 39).
- [Model Context Protocol (MCP)](https://docs.langchain4j.dev/tutorials/mcp):
  the LangChain4j MCP client and `McpToolProvider` (Part III, 41–47).
- [`DefaultMcpClient.Builder`](https://docs.langchain4j.dev/apidocs/dev/langchain4j/mcp/client/DefaultMcpClient.Builder.html):
  the 60 s `toolExecutionTimeout` (Part III, 50).

OpenRouter:

- [Audio](https://openrouter.ai/docs/features/multimodal/audio):
  "Audio output requires streaming (`stream: true`)", the rule behind attempt
  4 on **Music** and the OpenRouter row on **Why can't Spring AI reach it?**
  (Part I, 46–47).
- [Video Generation](https://openrouter.ai/docs/features/multimodal/video-generation):
  submit, poll, download, as on **Video** (Part I, 49).

Model Context Protocol:

- [Key changes in 2026-07-28](https://modelcontextprotocol.io/specification/2026-07-28/changelog)
  and
  [The 2026-07-28 Specification](https://blog.modelcontextprotocol.io/posts/2026-07-28/):
  every row of the table on **What 2026-07-28 changes** (Part III, 51). The
  same changelog dates the deprecation of HTTP+SSE to 2025-03-26
  (**Why SSE was replaced**, Part III, 26).

## How to read the notes

The notes below are for going through a PDF without the talk. Slide titles
are in bold, and the number after a title is its PDF page number.

## Notes for Part I

51 pages.

### One tool span name in every trace

Every trace screenshot (**Observability**, 19, **Traces**, 23, **Advisors
Inside the Loop**, 32, **Default**, 35, **Memory in the Loop**, 36) shows the
tool as `execute_tool <name>` with `gen_ai.operation.name=execute_tool`.
Spring AI 2.0 emits that span itself. `raw-agent` and the LangChain4j modules
create the same span by hand (`RawAgentLoop`, `RawAgentLoopService`, and
`TracingToolProvider` in `common`), so a tool call looks the same in all three.

### Which hooks the repo actually uses

Of the five on **Five ways around one loop** (25), two are wired in:
`ChatModelListener` (`GenAiMetricsChatModelListener`) and the `ToolProvider`
wrapper (`TracingToolProvider`), both in `common`.

The code on **Two callbacks instead of a wrapper** (26) is an illustration and
is not in any module. The reason is the one on the slide: with the default
`ToolArgumentsErrorHandler`, which rethrows, a tool call whose arguments don't
parse never reaches `afterToolExecution` (LangChain4j 1.16.3, `ToolService`),
so a span opened in `beforeToolExecution` would never be closed. The wrapper
opens and closes the span in one `try`/`finally`.

### One memory advisor, except on one slide

**Default** (35) and **Memory in the Loop** (36) use a single
`MessageChatMemoryAdvisor`, outside and inside the loop. **Memory Inside &&
Outside** (37) shows a bug from an earlier version of this repo:
`SpringAiConfig` also registered one through `defaultAdvisors(...)`, so every
request ran with two advisors on the same conversation id and the question was
saved twice. To reproduce it, inject `MessageWindowChatMemory chatMemory` into
the `ChatClient` bean in `SpringAiConfig`, add
`.defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())` and
call `/api/v1/agent/chat/memory-in-loop`.

### Versions

Spring Boot 4.0.4 · Spring AI 2.0.1 · LangChain4j 1.16.3 · Java 21.
The tool call limits on **Who stops the loop?** (39), 40 per tool and 150 per
turn, exist only from Spring AI 2.0.1 — 2.0.0 had no limit at all
([upgrade notes](https://docs.spring.io/spring-ai/reference/upgrade-notes.html#_new_tool_call_limits)).

## Notes for Part II

76 pages.

### Where `@Agent` is required

The arrows on **Prompt chaining → LangChain4j** (12) mark where `@Agent` is
required. Every interface built with `agentBuilder()` needs it: the builder
looks for the annotated method and fails with `No agent method found` without
it. On a typed workflow interface (`ScoutReportWorkflow`, and likewise
`RoutedAnswerWorkflow` and `LineupWorkflow`) it is optional. Leave it out and
the workflow still runs, under the default name `Sequential` and without a
description.

The description never reaches the agent's own model (**What the model actually
sees**, 19), and in a sequence it decides nothing, because the order comes from
`subAgents(...)`. It matters when a model has to choose: a supervisor lists
every sub-agent with its description in its own prompt.

### Every specialist has every tool

The diagram on **Routing** (21) gives each specialist its own tools. The code
does not. In Spring AI a single `milanAgent` with all four tools answers every
route, with a different system prompt per route (**Routing → Spring AI**, 23).
In LangChain4j all three specialists get the same `milanToolProvider`
(**Every specialist writes the same key**, 28). The squad specialist could
therefore call `getSecretRumors` and trigger the approval gate. Giving each
specialist only its own tools is the obvious next step, and routing is a
natural place for least privilege.

### Parallelization: same shape, different details

**It's quite simple** (38) holds for the shape: both modules fan out with
`CompletableFuture` on virtual threads and pick the best score in Java. The
details differ. The Spring AI version retries an unusable answer once with a
more explicit prompt, keeps the score as a boxed `Double` because the model
sometimes leaves it out, and catches errors per branch, so one timeout does
not fail the request. The LangChain4j version calls `join()` directly, so one
failed branch fails the whole request. The timings on **Results** (37)
therefore compare slightly different work.

**Let the model fill a record, let Java do the math** (39) puts the two modules
side by side. The record on the left is the Spring AI one (boxed `Double`,
`isUsable()`). The prompt on the right is what LangChain4j sent for its own
record, the one on slide 38 with a primitive `double`.

### The framework writes part of the prompt

When a call returns a typed result, the prompt the model receives ends with
text that is not in your code. LangChain4j appends instructions built from the
method's return type: "You must answer strictly in the following JSON
format: …" for a record (39), "You must answer strictly with one of these
enums: …" for an enum such as the router's `Route`. In Spring AI,
`.entity(...)` makes `BeanOutputConverter` generate a JSON Schema, and
`ChatModelCallAdvisor` appends it to the user message just before the call
(**Spring AI entity**, 40). The appended part starts at "Your response should
be in JSON format.", the first of the four sentences above the red box.

Both are requests, not guarantees. The model can still leave a field out,
which is why the Spring AI parallelization keeps its retry. Provider-enforced
schemas are opt-in: the `RESPONSE_FORMAT_JSON_SCHEMA` capability in
LangChain4j, `useProviderStructuredOutput()` in Spring AI.

### Two evaluator runs

The evaluator-optimizer section shows more than one run. In the Spring AI half
of **Results** (47), in the Spring AI **Trace** (52) and in the LangChain4j
**Trace** (62), the first lineup already cleared the 0.85 threshold (0.9, 0.9
and 1.0), so the fixer never ran. **More accurate trace** (53) and **Third
time's the charm** (54) show a Spring AI run that needed three laps: 0.8, 0.8,
1.0. The third lap is the short one, because an accepted lineup is not sent to
the fixer.

Whether a run loops depends on the first proposal. The scoring rules give 0.8
to a correct lineup and 0.9 to one that also states the formation and a shirt
number for every starter. The generator asks for a formation and receives the
squad with shirt numbers, so a first lineup often has both and passes straight
away.

Only the Spring AI module puts a span on every lap (`evaluator_iteration`, with
the score as an attribute). A hand-written loop owns its counter, so that takes
a few lines. The LangChain4j loop could get the same: an `AgentListener` on the
scorer sees every call and its `Evaluation`, and `AgentMonitor` already numbers
the laps (`iterationIndex`).

### Orchestrator workers run one after another

The planner asks for independent subtasks, but both modules run the workers in
sequence (`plan.subtasks().stream().map(...)`). The times on **Results** (64)
include the sum of the worker calls. Running the workers the way
Parallelization does would cut that part down to the slowest one.

### Versions

Same as Part I, plus `langchain4j-agentic` 1.16.3-beta26 from the LangChain4j
1.16.3 BOM. The agentic module is still on the beta line.

## Notes for Part III

53 pages.

### Two servers, two halves of the deck

Slides 6–24 are about `claude-mcp-server`: STDIO, started by Claude Desktop,
nine file tools plus two resources, two resource templates, four prompts and
three completions. Slides 25–49 are about `mcp-server`: Streamable HTTP on
port 8081, five tools (`get_game_stats`, `save_note`, `search_notes`,
`delete_note`, `get_weather`), called by `spring-ai-agent-mcp` and
`langchain4j-agent-mcp`. The annotation is the same `@McpTool` on both
(**Tools**, 16, and **The tool every demo uses**, 30). Only the transport and
the process that runs the method differ.

### Advertised and registered are two settings

The log on **What the server announces at startup** (13) comes in pairs: an
"Enable … capabilities" line from `spring.ai.mcp.server.capabilities.*`, then
a "Registered …" line that counts what the annotation scanner found. The two
are configured independently. `claude-mcp-server` turns all four on because it
has something behind each. `mcp-server` has tools only, so **MCP over HTTP**
(27) turns the other three off.

The `resource: true` row on **Where it breaks?** (52) is `mcp-server` before
those three lines existed: the defaults advertised resources and prompts, and
`resources/list` returned an empty array.

### Why the log path is absolute

**Spring Boot configuration** (9) has `logging.file.name:
logs/claude-mcp-server.log`, which is relative to the working directory.
Claude Desktop does not start the JVM in the project root: the first line of
the log on slide 13 says `started by konra in C:\WINDOWS\system32`. That is why
**Claude Desktop setup** (7) passes an absolute `-Dlogging.file.name`, and it
is the second row on slide 52.

### Which branch wrote the header

**Spring AI needs two hooks** (33) to **Same thread, same traceparent** (36)
walk through `McpTracePropagationConfig`. Step 1 copies the current trace
context into `McpTransportContext` on the calling thread. Step 2 runs right
before every `POST /mcp` and writes the `traceparent` header: from the
thread-local when there is one, otherwise from the copy.

In the run on the slides the thread-local was there, so the copy was not used.
Slide 36 shows it in the debugger, and the log on **Same trace_id in the logs**
(39) says `Injected trace context (thread-local)`. The copy is the fallback
for a request that is sent from another thread.

Step 2 has to be registered as
`McpClientCustomizer<HttpClientStreamableHttpTransport.Builder>`. Spring AI 2.0
no longer applies a standalone `McpSyncHttpClientRequestCustomizer` bean: the
context starts, no error is logged, and the header is never written. That is
the `@Bean …RequestCustomizer` row on slide 52.

### Two traces is the default on the LangChain4j side

`langchain4j-agent-mcp` sends no `traceparent` unless
`lc4j.mcp.trace-propagation.enabled` is `true`, and the default is `false`.
**What we miss?** (43) is that default: one trace for the agent and a separate
one for `mcp-server`, found with the TraceQL query at the top of the slide.
**After the switch** (48) is the same question asked again after a restart
with `--lc4j.mcp.trace-propagation.enabled=true`: one trace, two services.

On **Two beans, one name** (45) the two `@Bean` methods share the bean name
`javaMcpClient` and have different Java method names on purpose. Spring
remembers a skipped `@Bean` method by its method name, so two overloads with
the same name would both be skipped as soon as the first one is.

**One header supplier** (47) is enough here because `customHeaders(...)` runs
on the thread that executes the tool, where the `execute_tool` span is already
open. That stops being true if `AiServices` is set to run tools concurrently
on an executor.

### The tool timeout is shorter than the approval gate

The **Tool timeout** row on **Two clients, one protocol** (50) gives the client
defaults: 20 s in Spring AI (`spring.ai.mcp.client.request-timeout`) and 60 s
in LangChain4j (`toolExecutionTimeout`). Neither MCP agent in this repo changes
them. `save_note` and `delete_note` wait for a human for up to 10 minutes
(`xkondix.approval.timeout-minutes`), so an approval that takes longer than
the client timeout is one the agent no longer waits for. The average waits
charted on **Last 3 hours** (22) stay under 20 s, so that case never appears
on the slides. If approvals can take longer, raise the client timeout to
match.

### Which protocol revision the slides show

The slides were recorded on MCP 2025-11-25, the revision named on **The server
speaks MCP 2025-11-25** (5). The `initialize` exchange on **The handshake, as
Claude Desktop saw it** (14) and the `Mcp-Session-Id` header on **Why two
traces?** (44) belong to that revision. **What 2026-07-28 changes** (51) lists
what the next revision changes, and none of it is implemented in this repo.

**Why SSE was replaced** (26) is an older change: Streamable HTTP has been in
the specification since 2025-03-26, and Spring AI 2.0 marks its SSE server
transport as deprecated in favour of it.

### Versions

Same as Part I. `claude-mcp-server` is outside the Maven reactor and has its
own parent, on the same version set. The Spring AI modules use the official
MCP Java SDK, whose version the Spring AI BOM manages. LangChain4j uses its
own client, `langchain4j-mcp`.
