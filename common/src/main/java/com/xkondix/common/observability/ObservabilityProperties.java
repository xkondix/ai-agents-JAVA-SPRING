package com.xkondix.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration for the shared observability auto-configuration.
 *
 * Example (application.yml):
 *   xkondix:
 *     observability:
 *       excluded-path-prefixes:
 *         - /actuator
 *         - /swagger-ui
 *
 * Uses a record + @DefaultValue — Spring Boot binds constructor parameters
 * directly, no setters needed.
 */
@ConfigurationProperties(prefix = "xkondix.observability")
public record ObservabilityProperties(
        @DefaultValue("/actuator") List<String> excludedPathPrefixes
) {
}
