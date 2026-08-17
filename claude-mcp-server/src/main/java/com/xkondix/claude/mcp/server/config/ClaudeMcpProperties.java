package com.xkondix.claude.mcp.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Configuration of the file-access sandbox for this MCP server.
 *
 * Record + @DefaultValue (the same pattern as ObservabilityProperties in
 * `common`): Spring Boot binds constructor parameters directly — no setters,
 * fully immutable, and the defaults live in EXACTLY ONE place.
 *
 * Why that matters here: the previous version was a @Data class whose field
 * initializers duplicated the list from application.yml — and the two had
 * already drifted apart (the class was missing .tsx and .imports). As long as
 * the yml loads, its values win and nobody notices; the day it does not
 * (a repackaged JAR, a profile mishap), the server silently falls back to a
 * DIFFERENT allow-list. Defaults declared once cannot drift.
 *
 * The yml still overrides everything — it is the place to tweak the sandbox
 * per machine; these values are the fallback, not the configuration.
 *
 * Registered via @EnableConfigurationProperties on the application class,
 * so no @Component is needed.
 *
 * PREFIX: `claude-mcp`, renamed together with the module (was `code-mcp`).
 * A prefix that no longer matches the yml binds nothing and falls back to the
 * defaults below — no error, no log line — so the two must be changed together.
 *
 * @param projectRoot       absolute path to the project this server may touch;
 *                          every relative path is resolved against it and
 *                          validated to prevent path traversal
 * @param allowedExtensions file types readable AND writable by the tools
 * @param ignoredDirs       directories skipped when walking the tree
 */
@ConfigurationProperties(prefix = "claude-mcp")
public record ClaudeMcpProperties(

        @DefaultValue("${user.dir}")
        String projectRoot,

        @DefaultValue({
                ".java", ".xml", ".yml", ".yaml", ".properties",
                ".md", ".json", ".py", ".jsx", ".js", ".ts", ".tsx",
                ".sql", ".txt",
                // Spring Boot auto-configuration registration files
                // (META-INF/spring/...AutoConfiguration.imports)
                ".imports"
        })
        List<String> allowedExtensions,

        @DefaultValue({
                ".git", "target", "node_modules", ".idea", ".mvn",
                "__pycache__", "logs"
        })
        List<String> ignoredDirs
) {
}
