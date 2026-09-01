package com.xkondix.rawagent.tools;

import com.xkondix.rawagent.model.ToolDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for manual tool registration and routing.
 * Uses a real ObjectMapper — argument parsing is part of the contract.
 *
 * JACKSON 3 (Spring Boot 4): tools.jackson.databind.ObjectMapper, and
 * JsonNode.asText() is superseded by asString().
 */
class DemoToolsTest {

    private DemoTools demoTools;

    @BeforeEach
    void setUp() {
        demoTools = new DemoTools(new ObjectMapper());
    }

    // ── Definitions (the "tools" array sent to the API) ───────────────────

    @Test
    void exposesThreeToolDefinitions() {
        List<ToolDefinition> definitions = demoTools.getDefinitions();

        assertThat(definitions)
                .extracting(d -> d.function().name())
                .containsExactly("getCurrentTime", "calculateSquare", "getWeather");
    }

    @Test
    void calculateSquareDefinition_declaresRequiredNumberParam() {
        ToolDefinition def = demoTools.getDefinitions().stream()
                .filter(d -> d.function().name().equals("calculateSquare"))
                .findFirst()
                .orElseThrow();

        var params = def.function().parameters();
        assertThat(params.get("properties").has("number")).isTrue();
        assertThat(params.get("required").get(0).asString()).isEqualTo("number");
    }

    // ── Routing / execution ───────────────────────────────────────────────

    @Test
    void executesCalculateSquare() {
        String result = demoTools.execute("calculateSquare", "{\"number\":7}");

        assertThat(result).isEqualTo("49");
    }

    @Test
    void executesGetWeather() {
        String result = demoTools.execute("getWeather", "{\"city\":\"Krakow\"}");

        assertThat(result).contains("Krakow");
    }

    @Test
    void executesGetCurrentTime_inExpectedFormat() {
        String result = demoTools.execute("getCurrentTime", "{}");

        assertThat(result).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
    }

    @Test
    void reportsUnknownTool() {
        String result = demoTools.execute("launchRocket", "{}");

        assertThat(result).isEqualTo("Unknown tool: launchRocket");
    }

    @Test
    void returnsToolError_whenArgumentsAreMalformed() {
        // Model sent broken JSON — must degrade gracefully, not throw,
        // because the result goes back into the conversation history.
        String result = demoTools.execute("calculateSquare", "{not-json");

        assertThat(result).startsWith("TOOL ERROR");
    }

    @Test
    void returnsToolError_whenRequiredArgumentIsMissing() {
        String result = demoTools.execute("calculateSquare", "{}");

        assertThat(result).startsWith("TOOL ERROR");
    }
}
