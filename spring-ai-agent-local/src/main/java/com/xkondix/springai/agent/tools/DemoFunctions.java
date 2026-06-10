package com.xkondix.springai.agent.tools;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Spring AI @Tool approach.
 *
 * Very similar to LangChain4j @Tool but:
 * - Uses org.springframework.ai.tool.annotation.Tool
 * - @ToolParam instead of @P
 * - Registered via ChatClient.tools(...) in the request builder
 */
@Slf4j
@Component
public class DemoFunctions {

    @Tool(description = "Returns the current date and time")
    public String getCurrentTime() {
        log.info("Spring AI Tool: getCurrentTime");
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool(description = "Calculates the square of a number")
    public int calculateSquare(
            @ToolParam(description = "The number to square") int number) {
        log.info("Spring AI Tool: calculateSquare({})", number);
        return number * number;
    }

    @Tool(description = "Gets mock weather for a city")
    public String getWeather(
            @ToolParam(description = "City name") String city) {
        log.info("Spring AI Tool: getWeather({})", city);
        return String.format("Weather in %s: 20C, sunny", city);
    }
}
