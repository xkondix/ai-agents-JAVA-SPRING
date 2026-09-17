# Chat UI — AI Agents

React interface for the ai-agents-JAVA-SPRING project: agent chat,
human-in-the-loop approvals, the Patterns Lab and the Multimodal Lab.

## Stack
- React 18 + Vite
- Tailwind CSS
- react-markdown + syntax highlighting
- lucide-react (icons)
- react-router-dom

## Run

```bash
cd chat-ui
npm install
npm run dev
# open http://localhost:3000
```

Most backend calls go through the Vite dev proxy (`vite.config.js`), so no CORS
setup is needed on the module side. **Changing the proxy requires restarting
the dev server** — the config is read at startup only.

Two modules are called **directly** instead and enable CORS themselves:
mcp-server (8081) for approvals, and multimodal-lab (8089), which serves media
files the browser has to fetch by absolute URL anyway.

> CORS failures here look like something else entirely. A multipart POST is a
> "simple" request, so the browser sends it **without** a preflight: the server
> receives it, does the work, logs a clean success — and then the response is
> thrown away for lack of an `Access-Control-Allow-Origin` header. The page
> says "Failed to fetch" while the backend log says everything worked.

## Pages

| Route | What it does |
|-------|--------------|
| `/` | Chat with a selected agent |
| `/approvals` | Human-in-the-loop inbox — pending approvals from every source |
| `/patterns` | Patterns Lab — runs a workflow pattern against both pattern modules and compares the results |
| `/multimodal` | Multimodal Lab — one router, five tools, plus a gallery of everything produced |
| `/multimodal/view` | Full-screen viewer for a single artifact |

### `/` — chat
1. Polls each agent's health (Spring Actuator) every 15 seconds.
2. Shows agent status in the sidebar; offline agents are marked as such.
3. After selecting an agent, chats directly with it.
4. Handles JSON responses and SSE streaming.

Patterns Lab and Multimodal Lab are linked from the sidebar rather than listed
as agents: both need a page of their own (side-by-side results, a file picker,
a gallery) that the shared `ChatWindow` has no reason to carry.

### `/approvals` — Approval Flow
Polls **three** sources every 5 seconds and merges the results:
mcp-server (8081, called directly — it enables CORS), patterns-langchain4j
(8087) and patterns-spring-ai (8088) through the proxy. Each request carries
its source, because approve/reject must go back to the module that is
actually blocking. A live timer shows how long the agent has been waiting
(amber at 8 min, red at 9, timeout at 10).

### `/patterns` — Patterns Lab
One card per workflow pattern: input fields matched to the pattern
(season / free text / none), a target-language dropdown for chaining,
a **Run both** button that fires at 8087 and 8088 in parallel, results side by
side with millisecond timings, and an SVG flow diagram of the pattern.

Each result panel labels what that module's implementation **actually is**,
per pattern — `Agentic DSL — loopBuilder + exitCondition` for the evaluator,
`plain Java — CompletableFuture (DSL adds nothing here)` for parallelization.
A blanket "Agentic DSL" badge would be true for three patterns and false for
two.

Colour convention: indigo/emerald identify the frameworks in the result
panels; the diagrams use a separate palette for node roles (data, LLM call,
decision/control) because a pattern is framework-agnostic.

> Timings in the badges are measured end-to-end **in the browser** (proxy
> included), so they run slightly higher than the spans in Tempo. For
> orchestrator-workers they are not comparable at all, and the card says so:
> the two modules do different amounts of work.

### `/multimodal` — Multimodal Lab
Three columns, each answering a different question:

- **left** — the gallery: everything the lab has produced, grouped by kind
  (images, video, music, narration, text, uploads). Defaults to *Everything*
  rather than the current conversation, because the conversation id is
  regenerated on every page load and a per-thread gallery would go blank after
  a refresh while the files sat in `./media`. Clicking an entry opens the
  viewer route.
- **centre** — the conversation. Artifacts render inline in the bubbles as
  well; that is not duplication, since in the chat they answer a question and
  in the gallery they are a catalogue.
- **right** — the flow diagram and an explanation of the design.

Kind comes from the **server**, not from the file extension: a narration mp3
and a Lyria song are the same format and completely different things to a
listener. Only files found by the disk scan — older runs, lost conversation
ids — fall back to guessing, and they are labelled "from an earlier session".

Voice input is built (`useRecorder.js`, multipart `audio` part, transcription
on the backend) but **switched off**: the OpenRouter transcription slug was
never confirmed and the endpoint answered 500. Re-enabling is importing the
hook and putting two buttons back.

### `/multimodal/view` — artifact viewer
A route rather than a modal, so an artifact can be linked, reloaded and shown
full-screen on a projector. State travels in query params for the same reason:
router state does not survive a reload or a pasted link.

## Adding a new agent

Edit `src/agents.js` — add an entry and the matching proxy in `vite.config.js`.
Patterns and approval sources are configured the same way: `src/patterns.js`
and `src/api/approvalsApi.js`. Multimodal talks to a fixed base URL in
`src/api/multimodalApi.js`.

## Ports used

| Module | Port | Used by |
|--------|------|---------|
| mcp-server | 8081 | approvals (direct, CORS) |
| langchain4j-agent-local | 8082 | chat |
| langchain4j-agent-mcp | 8083 | chat |
| spring-ai-agent-local | 8084 | chat |
| spring-ai-agent-mcp | 8085 | chat |
| patterns-langchain4j | 8087 | Patterns Lab, approvals |
| patterns-spring-ai | 8088 | Patterns Lab, approvals |
| multimodal-lab | 8089 | Multimodal Lab, media files (direct, CORS) |
| raw-agent | 8090 | chat |
