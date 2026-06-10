package com.xkondix.rawagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** HTTP response body from Ollama / OpenAI API */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChatResponse(
        String id,
        List<Choice> choices,
        Usage usage
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(
            int index,
            Message message,
            @JsonProperty("finish_reason") String finishReason
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) {}

    public Message firstMessage() {
        if (choices == null || choices.isEmpty()) return null;
        return choices.get(0).message();
    }

    public boolean hasToolCalls() {
        Message msg = firstMessage();
        return msg != null
                && msg.toolCalls() != null
                && !msg.toolCalls().isEmpty();
    }
}
