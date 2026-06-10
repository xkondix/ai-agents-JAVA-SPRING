package com.xkondix.rawagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "raw-agent")
public class RawAgentProperties {

    /** "ollama" or "openai" */
    private String provider = "ollama";

    private OllamaConfig ollama = new OllamaConfig();
    private OpenAiConfig openai = new OpenAiConfig();

    @Data
    public static class OllamaConfig {
        private String baseUrl = "http://localhost:11434";
        private String model = "gemma3:4b";
        private double temperature = 0.3;
        private int timeoutSeconds = 120;
    }

    @Data
    public static class OpenAiConfig {
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private String model = "gpt-4o-mini";
        private double temperature = 0.3;
        private int timeoutSeconds = 60;
    }
}
