package com.xkondix.lc4j.agent.service;

import com.xkondix.lc4j.agent.tools.DemoTools;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class RawToolExecutor {

    private final DemoTools demoTools;

    public List<ToolSpecification> getToolSpecs() {
        return ToolSpecifications.toolSpecificationsFrom(demoTools);
    }

    public String execute(String toolName, String arguments) {
        return switch (toolName) {
            case "getCurrentTime" -> demoTools.getCurrentTime();
            case "calculateSquare" -> {
                int n = extractInt(arguments, "number");
                yield String.valueOf(demoTools.calculateSquare(n));
            }
            case "getWeather" -> {
                String city = extractString(arguments, "city");
                yield demoTools.getWeather(city);
            }
            default -> "Unknown tool: " + toolName;
        };
    }

    private String extractString(String json, String key) {
        try {
            String search = "\"" + key + "\":\"";
            int start = json.indexOf(search) + search.length();
            int end   = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return "unknown";
        }
    }

    private int extractInt(String json, String key) {
        try {
            String search = "\"" + key + "\":";
            int start = json.indexOf(search) + search.length();
            int end   = json.indexOf(",", start);
            if (end == -1) end = json.indexOf("}", start);
            return Integer.parseInt(json.substring(start, end).trim());
        } catch (Exception e) {
            return 0;
        }
    }
}