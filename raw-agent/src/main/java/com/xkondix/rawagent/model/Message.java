package com.xkondix.rawagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Represents a single message in the conversation.
 * Maps directly to OpenAI-compatible API message format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Message(
        String role,
        String content,
        @JsonProperty("tool_calls") List<ToolCall> toolCalls,
        @JsonProperty("tool_call_id") String toolCallId
) {
    /** System message */
    public static Message system(String content) {
        return new Message("system", content, null, null);
    }

    /** User message */
    public static Message user(String content) {
        return new Message("user", content, null, null);
    }

    /** Assistant message with tool calls */
    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message("assistant", content, toolCalls, null);
    }

    /** Tool result message */
    public static Message tool(String toolCallId, String content) {
        return new Message("tool", content, null, toolCallId);
    }
}
