package com.xkondix.rawagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** HTTP request body sent to Ollama / OpenAI API */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatRequest(
        String model,
        List<Message> messages,
        List<ToolDefinition> tools,
        double temperature,
        @JsonProperty("stream") boolean stream
) {}
