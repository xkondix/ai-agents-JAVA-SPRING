package com.xkondix.claude.mcp.server;

import com.xkondix.claude.mcp.server.config.CodeMcpProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * MCP server dedicated to Claude Desktop — exposes this project's files as
 * MCP tools. Runs over STDIO as a subprocess started by the desktop app.
 *
 * CodeMcpProperties is a record, so it is registered explicitly here
 * (records cannot be @Component-scanned as configuration properties).
 *
 * No McpToolsConfig anymore: in Spring AI 2.0 the annotation scanner picks up
 * @McpTool methods on any bean under this package. The old
 * MethodToolCallbackProvider registration is gone — it was the path that
 * silently stopped binding arguments after the 1.1.x rebuild.
 *
 * THIS PACKAGE IS THE SCAN ROOT. Everything the server exposes lives below it
 * (.tools, .config). A wrong package here does not fail the build and does not
 * log anything — it just starts a server with zero tools, so keep the
 * declaration and the directory in sync.
 */
@SpringBootApplication
@EnableConfigurationProperties(CodeMcpProperties.class)
public class CodeMcpServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CodeMcpServerApplication.class, args);
    }
}
