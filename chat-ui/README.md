# Chat UI — AI Agents

Chat interface for the ai-agents-JAVA-SPRING project.

## Stack
- React 18 + Vite
- Tailwind CSS
- react-markdown + syntax highlighting
- lucide-react (ikony)

## Run

```bash
cd chat-ui
npm install
npm run dev
# open http://localhost:3000
```

## How it works

1. Every 15 seconds it checks each agent’s health (Spring Actuator).
2. It shows only active agents in the sidebar.
3. After selecting an agent, it opens a chat directly with that agent.
4. It handles JSON responses and SSE streaming.
5. It supports uploading files and images.

## Dodawanie nowego agenta

Edit `src/agents.js` — add an entry and the corresponding proxy in `vite.config.js`.

## Ports
| Agent              | Port |
|--------------------|------|
| langchain4j-agent  | 8082 |
| langchain4j-mcp    | 8083 |
| spring-ai-agent    | 8084 |
| spring-ai-mcp      | 8085 |
