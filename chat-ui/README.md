# Chat UI — AI Agents

React interface for the ai-agents-JAVA-SPRING project: agent chat,
human-in-the-loop approvals and the Patterns Lab.

## Stack
- React 18 + Vite
- Tailwind CSS
- react-markdown + syntax highlighting
- lucide-react (icons)
- react-router-dom (three pages)

## Run

```bash
cd chat-ui
npm install
npm run dev
# open http://localhost:3000
```

All backend calls go through the Vite dev proxy (`vite.config.js`), so no CORS
setup is needed on the module side. **Changing the proxy requires restarting
the dev server** — the config is read at startup only.

## Pages

| Route | What it does |
|-------|--------------|
| `/` | Chat with a selected agent |
| `/approvals` | Human-in-the-loop inbox — pending approvals from every source |
| `/patterns` | Patterns Lab — runs a workflow pattern against both pattern modules and compares the results |

### `/` — chat
1. Polls each agent's health (Spring Actuator) every 15 seconds.
2. Shows agent status in the sidebar; offline agents are marked as such.
3. After selecting an agent, chats directly with it.
4. Handles JSON responses and SSE streaming.

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
Colour convention: indigo/emerald identify the frameworks in the result
panels; the diagrams use a separate palette for node roles (data, LLM call,
decision/control) because a pattern is framework-agnostic.

> Timings in the badges are measured end-to-end **in the browser** (proxy
> included), so they run slightly higher than the spans in Tempo.

## Adding a new agent

Edit `src/agents.js` — add an entry and the matching proxy in `vite.config.js`.
Patterns and approval sources are configured the same way: `src/patterns.js`
and `src/api/approvalsApi.js`.

## Ports used

| Module | Port | Used by |
|--------|------|---------|
| mcp-server | 8081 | approvals |
| langchain4j-agent-local | 8082 | chat |
| langchain4j-agent-mcp | 8083 | chat |
| spring-ai-agent-local | 8084 | chat |
| spring-ai-agent-mcp | 8085 | chat |
| patterns-langchain4j | 8087 | Patterns Lab, approvals |
| patterns-spring-ai | 8088 | Patterns Lab, approvals |
| raw-agent | 8090 | chat |
