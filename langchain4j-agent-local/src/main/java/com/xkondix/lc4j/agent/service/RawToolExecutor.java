package com.xkondix.lc4j.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xkondix.lc4j.agent.tools.DemoTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Routes tool calls from the agent loop to actual Java methods.
 * Uses Jackson ObjectMapper for reliable JSON argument parsing
 * instead of manual string manipulation.
 *
 * In production: use AiServices which handles this automatically.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RawToolExecutor {

    private final DemoTools demoTools;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ToolSpecification> getToolSpecs() {
        return ToolSpecifications.toolSpecificationsFrom(demoTools);
    }

    public String execute(String toolName, String arguments) {
        try {
            JsonNode args = objectMapper.readTree(arguments);
            return switch (toolName) {
                case "getCurrentTime"  -> demoTools.getCurrentTime();
                case "calculateSquare" -> {
                    int n = args.get("number").asInt();
                    yield String.valueOf(demoTools.calculateSquare(n));
                }
                case "getWeather" -> {
                    String city = args.get("city").asText();
                    yield demoTools.getWeather(city);
                }
                default -> "Unknown tool: " + toolName;
            };
        } catch (Exception e) {
            log.error("Failed to parse tool arguments for {}: {}", toolName, arguments, e);
            return "ERROR: Failed to parse arguments — " + e.getMessage();
        }
    }
}
