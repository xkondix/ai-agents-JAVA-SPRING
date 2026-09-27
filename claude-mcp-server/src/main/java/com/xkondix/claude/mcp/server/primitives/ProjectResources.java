package com.xkondix.claude.mcp.server.primitives;

import com.xkondix.claude.mcp.server.tools.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.stereotype.Service;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP RESOURCES — data this server holds, addressable by URI.
 *
 * ── HOW A RESOURCE DIFFERS FROM A TOOL, AND WHY BOTH EXIST HERE ───────────
 *
 * The server already has read_file. A resource is not a second way to do the
 * same thing:
 *
 *   a TOOL is an ACTION the MODEL decides to take. It has to know what to ask
 *   for — which means guessing a path, and being told when it guesses wrong.
 *
 *   a RESOURCE is DATA the SERVER advertises. The client lists it, shows it to
 *   the human, and the human attaches it. Nobody guesses anything.
 *
 * That difference is the whole point of exposing both. "Read
 * multimodal-lab/src/main/resources/application.yml" is a tool call that
 * depends on the model remembering a path; picking "multimodal-lab config"
 * from a menu is not.
 *
 * ── TEMPLATES ARE NOT LISTED; CONCRETE URIs ARE ────────────────────────────
 *
 * A URI with {variables} is published under resources/templates/list, not
 * resources/list. A client that only renders the latter — and several do —
 * shows nothing at all if every resource here is a template. So two of the
 * four below are concrete URIs with no variables, and they are the ones that
 * make the server look inhabited when you open the resource picker.
 *
 * ── project://modules IS THE INTERESTING ONE ───────────────────────────────
 *
 * It is not a file. There is no document anywhere in the repo that lists every
 * module with its port and application name — that information is spread
 * across eleven application.yml files, and the table in README.md is a
 * hand-maintained copy that has been wrong twice.
 *
 * A resource can be a VIEW the server computes. That is the part most easily
 * missed about the primitive: it is addressable data, not a file handle.
 *
 * ── PARAMETER NAMES MATTER HERE TOO ────────────────────────────────────────
 *
 * The method parameter of a templated resource is matched to the {variable}
 * BY NAME, through reflection — the same mechanism, and the same dependency
 * on the -parameters compiler flag, that publishes tool arguments as arg0
 * when it is missing. Here the symptom would be different and worse: the
 * template variable would simply never bind.
 */
@Slf4j
@Service
public class ProjectResources {

    /** Public doc name → path in the repo. Keys are what the client sees. */
    private static final Map<String, String> DOCS = new LinkedHashMap<>(Map.of(
            "readme", "README.md",
            "endpoints", "ENDPOINTS.md",
            "observability", "OBSERVABILITY.md",
            "patterns", "PATTERNS.md",
            "chat-ui", "chat-ui/README.md",
            "claude-mcp-server", "claude-mcp-server/README.md"));

    private final FileService fileService;
    private final McpPrimitiveTelemetry telemetry;

    public ProjectResources(FileService fileService, McpPrimitiveTelemetry telemetry) {
        this.fileService = fileService;
        this.telemetry = telemetry;
    }

    /** Names a completion provider can offer — see ProjectCompletions. */
    static List<String> documentNames() {
        return List.copyOf(DOCS.keySet());
    }

    // ── Concrete URIs — these are what resources/list returns ─────────────

    @McpResource(
            uri = "project://structure",
            name = "Project structure",
            title = "ai-agents-JAVA-SPRING — directory tree",
            description = "Directory tree of the whole project, ignoring .git, target, "
                    + "node_modules and .idea. Attach this to give the model the layout "
                    + "without it having to walk the tree tool call by tool call.",
            mimeType = "text/plain")
    public String structure() {
        return telemetry.traced("resource", "project://structure",
                () -> fileService.getProjectStructure(4));
    }

    @McpResource(
            uri = "project://modules",
            name = "Module inventory",
            title = "Modules, ports and application names",
            description = "Every Maven module with the port it listens on and its "
                    + "spring.application.name — read from the modules' own "
                    + "application.yml files, not from a hand-maintained table.",
            mimeType = "text/plain")
    public String modules() {
        return telemetry.traced("resource", "project://modules", this::buildModuleInventory);
    }

    // ── Templated URIs — published as resource templates ──────────────────

    @McpResource(
            uri = "project://docs/{document}",
            name = "Project documentation",
            title = "README, ENDPOINTS, OBSERVABILITY, PATTERNS",
            description = "One of the project's markdown documents. Valid names: "
                    + "readme, endpoints, observability, patterns, chat-ui, claude-mcp-server.",
            mimeType = "text/markdown")
    public String document(String document) {
        String key = document == null ? "" : document.toLowerCase().replace(".md", "");
        String path = DOCS.get(key);
        if (path == null) {
            return "ERROR: Unknown document '" + document + "'. Available: "
                    + String.join(", ", DOCS.keySet());
        }
        return telemetry.traced("resource", "project://docs/" + key,
                () -> fileService.readFile(path));
    }

    @McpResource(
            uri = "project://module/{module}/config",
            name = "Module configuration",
            title = "application.yml of a module",
            description = "The application.yml of a single module, e.g. multimodal-lab or "
                    + "spring-ai-agent-mcp. These files carry most of the project's "
                    + "hard-won configuration notes in comments.",
            mimeType = "text/yaml")
    public String moduleConfig(String module) {
        if (module == null || module.isBlank()) {
            return "ERROR: No module given. See project://modules for the list.";
        }
        String path = module + "/src/main/resources/application.yml";
        return telemetry.traced("resource", "project://module/" + module + "/config",
                () -> fileService.readFile(path));
    }

    // ── The computed view ─────────────────────────────────────────────────

    /**
     * Walks the project root for directories containing a pom.xml and reads
     * each module's application.yml.
     *
     * Parsed with SnakeYAML rather than a regex. The naive approach — find the
     * first `port:` — happens to work today and breaks the moment a module
     * declares another port first; multimodal-lab already has
     * `spring.data.redis.port: 6379` a few lines below `server.port: 8089`.
     * SnakeYAML is already on the classpath via spring-boot-starter.
     *
     * loadAll(), not load(): several application.yml files in this project use
     * `---` to declare profile documents, and load() throws on a multi-document
     * stream. Only the first document is inspected, which is where server.port
     * and spring.application.name live in every module here.
     */
    private String buildModuleInventory() throws Exception {
        Path root = fileService.resolveAndValidate("");
        StringBuilder out = new StringBuilder("""
                ai-agents-JAVA-SPRING — module inventory
                (read from each module's application.yml, not from a doc)

                """);
        out.append(String.format("%-26s %-8s %s%n", "MODULE", "PORT", "APPLICATION NAME"));
        out.append("-".repeat(70)).append('\n');

        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("pom.xml")))
                    .sorted()
                    .forEach(dir -> {
                        String module = dir.getFileName().toString();
                        Path yml = dir.resolve("src/main/resources/application.yml");
                        String port = "-";
                        String name = "-";
                        if (Files.exists(yml)) {
                            try {
                                Map<String, Object> doc = firstDocument(Files.readString(yml));
                                port = String.valueOf(nested(doc, "server", "port"));
                                name = String.valueOf(nested(doc, "spring", "application", "name"));
                            } catch (Exception e) {
                                log.debug("cannot parse {}: {}", yml, e.getMessage());
                            }
                        }
                        out.append(String.format("%-26s %-8s %s%n", module, port, name));
                    });
        }

        out.append("""

                Modules with no port (common) are libraries; claude-mcp-server
                speaks MCP over STDIO and has no port by design.
                """);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstDocument(String yaml) {
        for (Object doc : new Yaml().loadAll(yaml)) {
            if (doc instanceof Map<?, ?> map) {
                return (Map<String, Object>) map;
            }
        }
        return Map.of();
    }

    /** Walks a nested map by key path; returns "-" rather than throwing. */
    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (!(current instanceof Map<?, ?> m)) {
                return "-";
            }
            current = ((Map<String, Object>) m).get(key);
            if (current == null) {
                return "-";
            }
        }
        return current;
    }
}
