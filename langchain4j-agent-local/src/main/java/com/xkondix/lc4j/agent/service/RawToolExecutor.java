package com.xkondix.lc4j.agent.service;

import com.xkondix.lc4j.agent.tools.DemoTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Routes tool calls from the agent loop to actual Java methods.
 * Uses Jackson ObjectMapper for reliable JSON argument parsing
 * instead of manual string manipulation.
 *
 * In production: use AiServices which handles this automatically.
 *
 * JACKSON 3 (Spring Boot 4): databind moved to the tools.jackson root package.
 * This class builds its own ObjectMapper rather than injecting the Boot one, so
 * it kept compiling after the upgrade and would have failed only at runtime —
 * unlike LlmClient in raw-agent, which injects the bean and therefore broke the
 * context at startup with a clear message. The quiet variant is the dangerous
 * one, which is why both were migrated together.
 *
 * JsonNode.asText() is superseded by asString() in Jackson 3; asText() still
 * exists but is legacy, so the read below uses the new name.
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
                    String city = args.get("city").asString();
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
