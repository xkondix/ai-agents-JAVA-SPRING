# ai-agents-JAVA-SPRING — Multi-Agent Systems

Continuation of the "LangChain4j with Spring Boot" presentation series.
Author: Konrad Kowalczyk | xkondix
After Devoxx Poland 2026 — includes A2A, OpenTelemetry GenAI, LangGraph4j concepts.

## Project Structure

```
ai-agents-JAVA-SPRING/
├── pom.xml                    # Parent POM (dependency management)
├── docker-compose.yml         # Ollama + Redis + Chroma + Jira + Grafana LGTM
├── common/                    # Shared DTOs (ChatRequest, ChatResponse)
├── mcp-server/                # Spring Boot MCP Server (port 8081)
├── code-mcp-server/           # MCP Server for project file access (port 8086)
├── raw-agent/                 # Pure agent loop — no AI framework (port 8090)
├── langchain4j-agent-local/   # LangChain4j: raw loop + AiServices + @Tool (port 8082)
├── langchain4j-agent-mcp/     # LangChain4j: MCP orchestrator (port 8083)
├── spring-ai-agent-local/     # Spring AI: ChatClient + Advisors + @Tool (port 8084)
├── spring-ai-agent-mcp/       # Spring AI: MCP orchestrator (port 8085)
└── chat-ui/                   # React chat interface (port 3000)
```

## Quick Start

```bash
# 1. Start infrastructure
docker compose up -d

# 2. Pull LLM models
docker exec -it ollama ollama pull gemma3:4b
docker exec -it ollama ollama pull llama3.1:8b

# 3. Build all modules
mvn clean install -DskipTests

# 4. Start modules (each in separate terminal)
cd mcp-server              && mvn spring-boot:run
cd code-mcp-server         && mvn spring-boot:run
cd raw-agent               && mvn spring-boot:run
cd langchain4j-agent-local && mvn spring-boot:run
cd langchain4j-agent-mcp   && mvn spring-boot:run
cd spring-ai-agent-local   && mvn spring-boot:run
cd spring-ai-agent-mcp     && mvn spring-boot:run

# 5. Start Chat UI
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
| raw-agent               | 8090 | Pure agent loop — no AI framework        |
| chat-ui                 | 3000 | React chat interface                     |
| Grafana                 | 3100 | Observability dashboards (LGTM stack)    |
| OTLP gRPC               | 4317 | OpenTelemetry collector                  |
| Jira                    | 8080 | Local Jira instance (integration demos)  |

## Agent Architecture

```
raw-agent               →  pure HTTP + Jackson, manual while-loop, zero AI frameworks
langchain4j-agent-local →  local @Tool methods (same process)
langchain4j-agent-mcp   →  MCP clients → mcp-server (8081) + code-mcp-server (8086)
spring-ai-agent-local   →  local @Tool methods (same process)
spring-ai-agent-mcp     →  MCP clients → mcp-server (8081) + code-mcp-server (8086)
```

## MCP Servers

### mcp-server (port 8081)
Tools: `get_game_stats`, `save_note`, `search_notes`, `get_weather`

### code-mcp-server (port 8086)
Tools (no approval): `read_file`, `list_files`, `get_project_structure`, `search_in_files`
Tools (approval required): `write_file`, `create_file`, `move_file`
Tools (double approval): `delete_file`

Approval UI: http://localhost:3000/approvals

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

## Framework Comparison

| Feature          | raw-agent      | LangChain4j            | Spring AI                     |
|------------------|----------------|------------------------|-------------------------------|
| Loop visibility  | Fully visible  | Hidden (AiServices)    | More explicit (ChatClient)    |
| Tool definition  | Manual JSON    | @Tool annotation       | @Tool annotation              |
| HTTP calls       | java.net.http  | Hidden                 | Hidden                        |
| Interceptors     | Manual         | Limited                | Advisor pattern               |
| MCP client       | N/A            | Manual @Bean config    | Autoconfigured from yml       |
| Versions         | —              | 1.15.1                 | 1.0.3                         |

## Observability

Grafana LGTM stack available at http://localhost:3100
OpenTelemetry endpoint: http://localhost:4318 (HTTP) / localhost:4317 (gRPC)

GenAI Semantic Conventions tracked per call:
- `gen_ai.provider.name`, `gen_ai.request.model`
- `gen_ai.usage.input_tokens`, `gen_ai.usage.output_tokens`
- `gen_ai.response.finish_reasons`
