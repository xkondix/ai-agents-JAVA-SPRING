package com.xkondix.mcpserver.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Weather tools exposed via MCP.
 * Spring AI @Tool approach — JSON Schema generated automatically.
 */
@Slf4j
@Service
public class WeatherTools {

    @Tool(description = "Get current weather for a city. Returns temperature, conditions and humidity.")
    public String get_weather(
            @ToolParam(description = "City name") String city) {
        log.info("[MCP] get_weather city={}", city);
        return String.format("Weather in %s: 22C, partly cloudy, humidity 65%%", city);
    }
}
