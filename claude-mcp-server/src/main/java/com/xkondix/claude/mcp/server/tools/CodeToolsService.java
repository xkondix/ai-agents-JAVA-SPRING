package com.xkondix.claude.mcp.server.tools;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * MCP tools for code editing — Spring AI 2.0 annotation API.
 *
 * @McpTool replaces the old @Tool + MethodToolCallbackProvider combination:
 * Spring AI generates the JSON schema from the method signature and the
 * auto-configuration registers annotated beans automatically. That old
 * registration path is exactly what broke after the 1.0.3 → 1.1.4 rebuild —
 * tools were visible but every argument arrived as null, because 1.1+ routes
 * MCP tool calls through the annotation scanner.
 *
 * PARAMETER NAMES COME FROM REFLECTION ONLY. @McpToolParam has just two
 * attributes, description and required — there is no `name`. So the JSON schema
 * can only get `path` or `content` if the class was compiled with -parameters;
 * without it every argument is published as arg0/arg1 and the client fills in
 * the wrong keys. spring-boot-starter-parent sets that flag, which is the whole
 * reason this module uses it as its parent instead of the project parent.
 * ToolSchemaContractTest asserts this, so the regression cannot come back
 * silently.
 *
 * Tool names ARE declared explicitly. Derivation from the method name works,
 * but it makes the wire contract a side effect of a refactor: rename the method
 * and the client silently loses a tool.
 *
 * NO APPROVAL FLOW: impossible over STDIO — no HTTP channel exists to unblock
 * a waiting tool call, and blocking the STDIO thread deadlocks the JSON-RPC
 * stream. The human-in-the-loop demo lives in mcp-server (port 8081).
 *
 * OBSERVABILITY: each call is wrapped in a "mcp_tool <name>" span created by
 * hand — there is no agent framework here to instrument, the caller is an
 * external client we do not control. It sends no traceparent, so every call
 * forms its own trace (Tempo shows one-span traces with Services: 1; that is
 * correct, not a propagation bug).
 */
@Slf4j
@Service
public class CodeToolsService {

    private final FileService fileService;

    /**
     * Nullable ON PURPOSE, resolved through ObjectProvider instead of plain
     * constructor injection.
     *
     * A hard `private final Tracer tracer` parameter is what kept this module
     * from starting: Spring Boot 4 moved tracing auto-configuration out of
     * actuator, so with the old dependency set the Micrometer Tracing API was
     * on the classpath but no Tracer bean was ever created — and a missing
     * bean fails the whole context. The pom now brings
     * spring-boot-starter-opentelemetry, so the bean SHOULD be there; this
     * fallback keeps the server usable anyway when tracing is switched off
     * (management.tracing.enabled=false) or in a test slice.
     *
     * A file-editing tool must not die because telemetry is unavailable.
     * (Tracer.NOOP would also work, but an explicit null check keeps the
     * "no telemetry" branch visible instead of hiding it behind a no-op.)
     *
     * @Nullable is JSpecify: org.springframework.lang.Nullable is deprecated
     * as of Spring Framework 7, which Boot 4 is built on.
     */
    private final @Nullable Tracer tracer;

    public CodeToolsService(FileService fileService, ObjectProvider<Tracer> tracerProvider) {
        this.fileService = fileService;
        this.tracer = tracerProvider.getIfAvailable();
        if (this.tracer == null) {
            log.warn("No Tracer bean available — mcp_tool spans are DISABLED, tools still work");
        }
    }

    /** Like Supplier, but allowed to throw — FileService methods declare IOException. */
    @FunctionalInterface
    private interface FileOperation {
        String execute() throws Exception;
    }

    // ── READ-ONLY ────────────────────────────────────────────────────────

    @McpTool(name = "read_file",
            description = "Read the content of a file in the ai-agents-JAVA-SPRING "
            + "project. Path must be relative to project root, e.g. "
            + "langchain4j-agent-local/src/main/resources/application.yml",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String read_file(
            @McpToolParam(description = "Relative path from project root", required = true)
            String path) {
        return traced("read_file", path, () -> fileService.readFile(path));
    }

    @McpTool(name = "list_files",
            description = "List files and directories at a given path. "
            + "Empty path lists the project root. Entries are prefixed [DIR] or [FILE].",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list_files(
            @McpToolParam(description = "Relative path to a directory; empty = project root",
                    required = false)
            String path) {
        return traced("list_files", path, () -> fileService.listFiles(path));
    }

    @McpTool(name = "get_project_structure",
            description = "Directory tree of the project. Ignores .git, target, "
            + "node_modules, .idea, __pycache__. Shows only allowed file extensions.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get_project_structure(
            @McpToolParam(description = "Max directory depth, default 4", required = false)
            Integer max_depth) {
        // Integer, not int: an absent optional argument arrives as null and a
        // primitive would blow up in reflection before the method even runs.
        int depth = (max_depth == null || max_depth <= 0) ? 4 : max_depth;
        return traced("get_project_structure", "depth=" + depth,
                () -> fileService.getProjectStructure(depth));
    }

    @McpTool(name = "search_in_files",
            description = "Search for text in all project files (case-insensitive). "
            + "Returns file paths and matching lines with line numbers.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String search_in_files(
            @McpToolParam(description = "Text to search for", required = true)
            String query,
            @McpToolParam(description = "Optional extension filter, e.g. .java", required = false)
            String extension) {
        String ext = (extension == null || extension.isBlank()) ? null : extension;
        return traced("search_in_files", "query=" + query + " ext=" + ext,
                () -> fileService.searchInFiles(query, ext));
    }

    // ── WRITE ────────────────────────────────────────────────────────────

    @McpTool(name = "write_file",
            description = "Overwrite an existing file with new content. "
            + "Use create_file for new files. Restricted to allowed extensions.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true, idempotentHint = true))
    public String write_file(
            @McpToolParam(description = "Relative path to the file", required = true)
            String path,
            @McpToolParam(description = "Full new content of the file", required = true)
            String content) {
        return traced("write_file", path, () -> fileService.writeFile(path, content));
    }

    @McpTool(name = "create_file",
            description = "Create a new file. Fails if it already exists — "
            + "use write_file to overwrite. Restricted to allowed extensions.")
    public String create_file(
            @McpToolParam(description = "Relative path for the new file", required = true)
            String path,
            @McpToolParam(description = "Initial file content", required = true)
            String content) {
        return traced("create_file", path, () -> fileService.createFile(path, content));
    }

    @McpTool(name = "move_file",
            description = "Move or rename a file. For directories use move_directory.")
    public String move_file(
            @McpToolParam(description = "Source relative path", required = true)
            String from_path,
            @McpToolParam(description = "Destination relative path", required = true)
            String to_path) {
        return traced("move_file", from_path + " -> " + to_path,
                () -> fileService.moveFile(from_path, to_path));
    }

    @McpTool(name = "move_directory",
            description = "Move or rename a directory, recursively. "
            + "For files use move_file.")
    public String move_directory(
            @McpToolParam(description = "Source relative directory path", required = true)
            String from_path,
            @McpToolParam(description = "Destination relative directory path", required = true)
            String to_path) {
        return traced("move_directory", from_path + " -> " + to_path,
                () -> fileService.moveDirectory(from_path, to_path));
    }

    // ── DESTRUCTIVE ──────────────────────────────────────────────────────

    @McpTool(name = "delete_file",
            description = "THIS ACTION CANNOT BE UNDONE. Permanently delete a file. "
            + "The confirm parameter must be exactly the string: DELETE",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public String delete_file(
            @McpToolParam(description = "Relative path to the file to delete", required = true)
            String path,
            @McpToolParam(description = "Must be exactly: DELETE", required = true)
            String confirm) {
        if (!"DELETE".equals(confirm)) {
            return "ERROR: confirm field must be exactly: DELETE";
        }
        return traced("delete_file", path, () -> fileService.deleteFile(path));
    }

    // ── Tracing helper ───────────────────────────────────────────────────

    /**
     * Wraps one tool invocation in a span and ALWAYS returns the operation's
     * result. Errors come back as an "ERROR: ..." string rather than an
     * exception: that text is what the model reads, and a readable message is
     * more useful to it than a protocol-level error it cannot inspect.
     *
     * NOTE ON LOG PLACEMENT. The "[TOOL] ..." line is emitted INSIDE the span
     * scope, not before it. When it sat above tracer.withSpan(...) it came out
     * with an empty correlation field while the FileService line right below it
     * carried the trace id — one orphan line per call, invisible in Loki when
     * filtering by trace. The Micrometer scope is a ThreadLocal, so only code
     * running between withSpan() and close() gets the ids into the MDC.
     *
     * Attribute names follow the OTel GenAI semantic conventions where they
     * exist (gen_ai.tool.name), plus `framework` — the same label the agent
     * modules use, so every panel in the Grafana dashboard can slice by it.
     * mcp.transport is deliberately NOT tagged here: it is a resource attribute
     * in application.yml, true for the whole service, so repeating it on every
     * span would just cost bytes.
     */
    private String traced(String toolName, String argsSummary, FileOperation operation) {
        if (tracer == null) {
            log.info("[TOOL] {}: {}", toolName, argsSummary);
            return execute(toolName, operation);
        }

        Span span = tracer.nextSpan().name("mcp_tool " + toolName);
        span.tag("gen_ai.operation.name", "execute_tool");
        span.tag("gen_ai.tool.name", toolName);
        span.tag("mcp.tool.args", String.valueOf(argsSummary));
        span.tag("framework", "spring-ai-mcp-server");

        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            log.info("[TOOL] {}: {}", toolName, argsSummary);
            String result = execute(toolName, operation);
            span.tag("mcp.tool.result.length", String.valueOf(result.length()));
            return result;
        } finally {
            span.end();
        }
    }

    private String execute(String toolName, FileOperation operation) {
        try {
            return operation.execute();
        } catch (Exception e) {
            log.error("[TOOL] {} failed: {}", toolName, e.getMessage());
            Span current = (tracer != null) ? tracer.currentSpan() : null;
            if (current != null) {
                current.tag("error.type", e.getClass().getSimpleName());
                current.error(e);
            }
            return "ERROR: " + e.getMessage();
        }
    }
}
