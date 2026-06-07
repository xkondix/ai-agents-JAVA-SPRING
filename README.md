# AI Sandbox v2 — Multi-Agent Systems

Continuation of the LangChain4j with Spring Boot presentation.
Author: Konrad Kowalczyk | xkondix AI Sandbox

## Project Structure

```
ai-sandbox-v2/
├── pom.xml                  # Parent POM (dependency management)
├── docker-compose.yml       # Ollama + Redis + ChromaDB
├── common/                  # Shared DTOs (ChatRequest, ChatResponse)
├── mcp-server/              # Spring Boot MCP Server (port 8081)
├── langchain4j-agent/       # LangChain4j: raw loop + AiServices (port 8082)
├── langchain4j-mcp/         # LangChain4j: MCP client + orchestrator (port 8083)
├── spring-ai-agent/         # Spring AI: ChatClient + Advisors (port 8084)
├── spring-ai-mcp/           # Spring AI: MCP client + orchestrator (port 8085)
└── python-agents/           # Python MCP agent (stdio transport)
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
cd mcp-server       && mvn spring-boot:run
cd langchain4j-agent && mvn spring-boot:run
cd langchain4j-mcp  && mvn spring-boot:run
cd spring-ai-agent  && mvn spring-boot:run
cd spring-ai-mcp    && mvn spring-boot:run
```

## Ports

| Module               | Port | Swagger UI                          |
|----------------------|------|-------------------------------------|
| mcp-server           | 8081 | http://localhost:8081/swagger-ui.html |
| langchain4j-agent    | 8082 | http://localhost:8082/swagger-ui.html |
| langchain4j-mcp      | 8083 | http://localhost:8083/swagger-ui.html |
| spring-ai-agent      | 8084 | http://localhost:8084/swagger-ui.html |
| spring-ai-mcp        | 8085 | http://localhost:8085/swagger-ui.html |

## Claude Code / Cursor MCP Config

Add to `.mcp.json` to connect Claude Code to the MCP server:

```json
{
  "mcpServers": {
    "aiSandbox": {
      "url": "http://localhost:8081/mcp/sse"
    }
  }
}
```

## Key Concepts

### The Agent Loop (same in every framework)
1. Build message list (system + user + history)
2. Call LLM with available tools
3. Tool calls in response? Execute them, add results to context, go to 2
4. No tool calls? Return final answer

### LangChain4j vs Spring AI
| Feature          | LangChain4j            | Spring AI                    |
|------------------|------------------------|------------------------------|
| Loop visibility  | Hidden (AiServices)    | More explicit (ChatClient)   |
| Tool definition  | @Tool annotation       | @Tool annotation (similar)   |
| Interceptors     | Limited                | Advisor pattern (powerful)   |
| MCP support      | langchain4j-mcp        | spring-ai-starter-mcp-client |
| Memory           | ChatMemory             | Repository pattern           |
| Versions         | 1.15.1                 | 1.0.3                        |
