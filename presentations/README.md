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
| IV | **How Do You Know It Works?** | Traces, metrics, logs and dashboards for everything your agents actually do | Grafana LGTM stack — see [`OBSERVABILITY.md`](../OBSERVABILITY.md) | planned |

Endpoints, tools and meters for every module: [`ENDPOINTS.md`](../ENDPOINTS.md).

## About the images

The illustrations in the slides are AI-generated and meant as humour. All
trademarks that appear in them belong to their respective owners. This is a
non-commercial, educational project, not affiliated with or endorsed by any of
them.

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

## Notes for Part II

Read these if you go through the PDF without the talk. Slide numbers are PDF
page numbers (76 pages).

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
be in JSON format.", four lines above the boxed schema.

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
