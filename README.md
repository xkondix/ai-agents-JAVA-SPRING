# ai-agents-JAVA-SPRING — Multi-Agent Systems

Continuation of the "LangChain4j with Spring Boot" presentation series.
Author: Konrad Kowalczyk | xkondix

## Project Structure

```
ai-agents-JAVA-SPRING/
├── pom.xml                    # Parent POM (dependency management)
├── docker-compose.yml         # Ollama + Redis + ChromaDB + Jira
├── common/                    # Shared DTOs (ChatRequest, ChatResponse)
├── mcp-server/                # Spring Boot MCP Server (port 8081)
├── code-mcp-server/           # MCP Server for project file access (port 8086)
├── langchain4j-agent-local/   # LangChain4j: raw loop + AiServices + local @Tool (port 8082)
├── langchain4j-agent-mcp/     # LangChain4j: MCP client orchestrator (port 8083)
├── spring-ai-agent-local/     # Spring AI: ChatClient + Advisors + local @Tool (port 8084)
├── spring-ai-agent-mcp/       # Spring AI: MCP client orchestrator (port 8085)
├── chat-ui/                   # React chat interface (port 3000)
└── python-agents/             # Python MCP agent (stdio transport)
    └── mcp_game_agent.py
```

## Quick Start

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Pull LLM models
docker exec -it ollama ollama pull gemma3:4b
docker exec -it ollama ollama pull llama3.1:8b

# 3. Install Python MCP agent dependencies
pip install -r python-agents/requirements.txt

# 4. Build
mvn clean install -DskipTests

# 5. Start modules (each in separate terminal)
cd mcp-server              && mvn spring-boot:run
cd code-mcp-server         && mvn spring-boot:run
cd langchain4j-agent-local && mvn spring-boot:run
cd langchain4j-agent-mcp   && mvn spring-boot:run
cd spring-ai-agent-local   && mvn spring-boot:run
cd spring-ai-agent-mcp     && mvn spring-boot:run

# 6. Start Chat UI
cd chat-ui && npm run dev
```

## Ports

| Module                  | Port | Description                              |
|-------------------------|------|------------------------------------------|
| mcp-server              | 8081 | MCP tools server (game stats, KB, etc)   |
| langchain4j-agent-local | 8082 | LangChain4j raw loop + AiServices        |
| langchain4j-agent-mcp   | 8083 | LangChain4j MCP orchestrator             |
| spring-ai-agent-local   | 8084 | Spring AI ChatClient + Advisors          |
| spring-ai-agent-mcp     | 8085 | Spring AI MCP orchestrator               |
| code-mcp-server         | 8086 | MCP tools for project file access        |
| chat-ui                 | 3000 | React chat interface                     |

## Agent Architecture

```
langchain4j-agent-local  →  local @Tool methods (same process)
langchain4j-agent-mcp    →  MCP clients → mcp-server + code-mcp-server + Python agent

spring-ai-agent-local    →  local @Tool methods (same process)
spring-ai-agent-mcp      →  MCP clients → mcp-server + code-mcp-server (autoconfigured)
```

## Claude Desktop MCP Config

File: `%APPDATA%\Claude\claude_desktop_config.json`

```json
{
  "mcpServers": {
    "ai-agents-code": {
      "command": "java",
      "args": [
        "-Dspring.ai.mcp.server.transport=STDIO",
        "-jar",
        "C:\\Users\\konra\\Desktop\\ai-agents-JAVA-SPRING\\code-mcp-server\\target\\code-mcp-server-1.0.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

## Key Concepts

### The Agent Loop (same in every framework)
1. Build message list (system + user + history)
2. Call LLM with available tools
3. Tool calls in response? Execute → add results to context → go to 2
4. No tool calls? Return final answer

### LangChain4j vs Spring AI

| Feature          | LangChain4j               | Spring AI                     |
|------------------|---------------------------|-------------------------------|
| Loop visibility  | Hidden (AiServices)       | More explicit (ChatClient)    |
| Tool definition  | @Tool annotation          | @Tool annotation (similar)    |
| Interceptors     | Limited                   | Advisor pattern (powerful)    |
| MCP client       | Manual @Bean config       | Autoconfigured from yml       |
| Memory           | ChatMemory                | Repository pattern            |
| Versions         | 1.15.1                    | 1.0.3                         |
