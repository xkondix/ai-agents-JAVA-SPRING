package com.xkondix.rawagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Represents a single tool call requested by the model.
 * The model returns this when it wants to call a function.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolCall(
        String id,
        String type,
        Function function
) {
    public record Function(String name, String arguments) {}
}
