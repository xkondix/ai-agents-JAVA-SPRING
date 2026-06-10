package com.xkondix.rawagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Defines a tool available to the model.
 * This is the JSON Schema that gets sent to the API in the "tools" array.
 *
 * Example JSON sent to API:
 * {
 *   "type": "function",
 *   "function": {
 *     "name": "getCurrentTime",
 *     "description": "Returns the current date and time",
 *     "parameters": { "type": "object", "properties": {}, "required": [] }
 *   }
 * }
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolDefinition(
        String type,
        FunctionDef function
) {
    public record FunctionDef(
            String name,
            String description,
            ObjectNode parameters
    ) {}
}
