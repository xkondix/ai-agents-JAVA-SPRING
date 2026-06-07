package com.xkondix.mcpserver.tools;

import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class WeatherTools {

    public McpServerFeatures.SyncToolSpecification getWeatherTool() {

        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "city": { "type": "string", "description": "City name" }
                  },
                  "required": ["city"]
                }
                """;

        return new McpServerFeatures.SyncToolSpecification(
                new McpSchema.Tool(
                        "get_weather",
                        "Get current weather for a city.",
                        schema),
                (exchange, args) -> {
                    String city = (String) args.getOrDefault("city", "unknown");
                    String weather = String.format(
                            "Weather in %s: 22C, partly cloudy, humidity 65%%", city);
                    return McpSchema.CallToolResult.builder()
                            .content(List.of(new McpSchema.TextContent(weather)))
                            .build();
                }
        );
    }
}