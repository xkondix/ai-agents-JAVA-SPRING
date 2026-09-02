package com.xkondix.rawagent.tools;

import com.xkondix.rawagent.model.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Demo tools registered manually — no @Tool annotation, no framework magic.
 *
 * Each tool has:
 *   1. A ToolDefinition (JSON Schema sent to the API)
 *   2. An execute() handler (called when the model requests it)
 *
 * This is exactly what @Tool annotation generates automatically
 * in LangChain4j and Spring AI.
 *
 * JACKSON 3 (Spring Boot 4): databind moved to the tools.jackson root package.
 * The node-building API used below (createObjectNode / put / putObject /
 * putArray / readTree) is unchanged. One rename does affect this class:
 * JsonNode.asText() is superseded by asString() — asText() still exists but is
 * legacy, so the reads below use the new name.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemoTools {

    private final ObjectMapper objectMapper;

    /**
     * Returns all tool definitions to send to the API.
     * This is the "tools" array in the HTTP request body.
     */
    public List<ToolDefinition> getDefinitions() {
        return List.of(
                getCurrentTimeDefinition(),
                calculateSquareDefinition(),
                getWeatherDefinition()
        );
    }

    /**
     * Routes tool call to the appropriate method.
     * This is what framework "tool routing" does automatically.
     */
    public String execute(String toolName, String arguments) {
        log.info("[TOOL] Executing: {} args={}", toolName, arguments);
        try {
            var args = objectMapper.readTree(arguments);
            return switch (toolName) {
                case "getCurrentTime"   -> getCurrentTime();
                case "calculateSquare"  -> String.valueOf(
                        calculateSquare(args.get("number").asInt()));
                case "getWeather"       -> getWeather(
                        args.get("city").asString());
                default -> "Unknown tool: " + toolName;
            };
        } catch (Exception e) {
            log.error("[TOOL] Failed: {}", toolName, e);
            return "TOOL ERROR: " + e.getMessage();
        }
    }

    // ── Tool implementations ──────────────────────────────────────────────

    private String getCurrentTime() {
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private int calculateSquare(int number) {
        return number * number;
    }

    private String getWeather(String city) {
        return String.format("Weather in %s: 22C, sunny", city);
    }

    // ── Tool definitions (JSON Schema) ────────────────────────────────────

    private ToolDefinition getCurrentTimeDefinition() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        params.putObject("properties");
        params.putArray("required");
        return new ToolDefinition("function",
                new ToolDefinition.FunctionDef(
                        "getCurrentTime",
                        "Returns the current date and time",
                        params));
    }

    private ToolDefinition calculateSquareDefinition() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        ObjectNode number = props.putObject("number");
        number.put("type", "integer");
        number.put("description", "The number to square");
        params.putArray("required").add("number");
        return new ToolDefinition("function",
                new ToolDefinition.FunctionDef(
                        "calculateSquare",
                        "Calculates the square of a given number",
                        params));
    }

    private ToolDefinition getWeatherDefinition() {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("type", "object");
        ObjectNode props = params.putObject("properties");
        ObjectNode city = props.putObject("city");
        city.put("type", "string");
        city.put("description", "Name of the city");
        params.putArray("required").add("city");
        return new ToolDefinition("function",
                new ToolDefinition.FunctionDef(
                        "getWeather",
                        "Gets mock weather for a city",
                        params));
    }
}
