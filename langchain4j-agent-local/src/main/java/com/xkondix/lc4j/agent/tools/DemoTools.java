package com.xkondix.lc4j.agent.tools;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple @Tool methods — LangChain4j annotation approach.
 * The framework auto-generates JSON Schema from these annotations.
 */
@Slf4j
@Component
public class DemoTools {

    @Tool("Returns the current date and time")
    public String getCurrentTime() {
        log.info("Tool executed: getCurrentTime");
        return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    @Tool("Calculates the square of a given number")
    public int calculateSquare(@P("The number to square") int number) {
        log.info("Tool executed: calculateSquare({})", number);
        return number * number;
    }

    @Tool("Gets mock weather for a city")
    public String getWeather(@P("Name of the city") String city) {
        log.info("Tool executed: getWeather({})", city);
        return String.format("Weather in %s: 20C, sunny", city);
    }
}
