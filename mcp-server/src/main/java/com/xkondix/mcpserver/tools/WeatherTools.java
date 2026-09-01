package com.xkondix.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;

/**
 * Weather tools exposed via MCP.
 *
 * SPRING AI 2.0 — @McpTool, NOT @Tool. See GameStatsTools for why the two
 * annotations now mean different things.
 */
@Slf4j
@Service
public class WeatherTools {

    @McpTool(name = "get_weather",
            description = "Get current weather for a city. "
            + "Returns temperature, conditions and humidity.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get_weather(
            @McpToolParam(description = "City name", required = true)
            String city) {
        log.info("[MCP] get_weather city={}", city);
        return String.format("Weather in %s: 22C, partly cloudy, humidity 65%%", city);
    }
}
