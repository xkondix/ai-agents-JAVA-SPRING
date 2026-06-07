package com.xkondix.lc4j.mcp.config;

import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.util.List;

@Slf4j
@Configuration
public class McpClientConfig {

    @Bean(name = "javaMcpClient")
    public McpClient javaMcpClient() {
        McpTransport transport = new StreamableHttpMcpTransport.Builder()
                .url("http://localhost:8081/mcp")  // /mcp nie /mcp/sse
                .logRequests(true)
                .logResponses(true)
                .build();

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("langchain4j-java-client")
                .clientVersion("1.0")
                .build();
    }

    @Bean(name = "pythonMcpClient")
    public McpClient pythonMcpClient() {
        McpTransport transport = new StdioMcpTransport.Builder()
                .command(List.of("python3",
                        "../python-agents/mcp_game_agent.py"))
                .logEvents(true)
                .build();

        return new DefaultMcpClient.Builder()
                .transport(transport)
                .clientName("langchain4j-python-client")
                .clientVersion("1.0")
                .build();
    }
}