package com.xkondix.mcpserver.config;

import com.xkondix.common.observability.McpToolTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared MCP tool telemetry (module `common`) into this server.
 *
 * Before this bean existed, mcp-server had NO tool-level spans or metrics: in
 * the propagated trace from spring-ai-agent-mcp the server side showed only
 * `http post /mcp`, and the "MCP tools" dashboard panels were fed exclusively
 * by claude-mcp-server — i.e. they were empty during a demo unless Claude
 * Desktop happened to be running.
 *
 * Both dependencies are resolved through ObjectProvider so the server starts
 * without tracing (management.tracing.enabled=false) or in a test slice — the
 * wrapper degrades instead of failing the context.
 */
@Configuration
public class McpTelemetryConfig {

    @Bean
    McpToolTelemetry mcpToolTelemetry(ObjectProvider<Tracer> tracerProvider,
                                      ObjectProvider<MeterRegistry> registryProvider) {
        return new McpToolTelemetry(
                tracerProvider.getIfAvailable(),
                registryProvider.getIfAvailable(),
                McpToolTelemetry.FRAMEWORK_SPRING_AI);
    }
}
