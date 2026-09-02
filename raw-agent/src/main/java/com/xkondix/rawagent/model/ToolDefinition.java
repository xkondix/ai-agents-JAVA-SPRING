package com.xkondix.rawagent.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.node.ObjectNode;

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
 *
 * JACKSON 3 (Spring Boot 4). Note the two different packages above — they are
 * not a mistake:
 *   com.fasterxml.jackson.annotation.*  ANNOTATIONS stayed where they were.
 *                                       Jackson 3 still uses jackson-annotations
 *                                       2.x, so @JsonInclude / @JsonProperty
 *                                       imports do NOT change.
 *   tools.jackson.databind.*            The databind API moved to a new root
 *                                       package for Jackson 3.
 * Boot 4 auto-configures a tools.jackson.databind.ObjectMapper. Jackson 2 is
 * still MANAGED by the Boot BOM, so old imports keep compiling — but no bean of
 * the old type exists, and injecting one fails at startup with "required a bean
 * of type 'com.fasterxml.jackson.databind.ObjectMapper' that could not be found".
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
