package com.xkondix.claude.mcp.server.tools;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
 * OBSERVABILITY: each call is wrapped by hand in a SERVER-kind span plus three
 * meters. There is no agent framework here to instrument — the caller is an
 * external client we do not control. It sends no traceparent, so every call
 * forms its own trace (Tempo shows one-span traces with Services: 1; that is
 * correct, not a propagation bug).
 */
@Slf4j
@Service
public class CodeToolsService {

    private static final String FRAMEWORK = "spring-ai-mcp-server";

    /** Payload directions, mirroring gen_ai_token_type=input|output on the agent panels. */
    private static final String REQUEST = "request";
    private static final String RESPONSE = "response";

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

    /**
     * Same lesson applied a second time, but resolved differently: a missing
     * MeterRegistry falls back to a SimpleMeterRegistry rather than to null.
     * Meters are recorded unconditionally, so a no-op sink removes a null check
     * from every call path — whereas the span branch stays explicit because
     * "no tracing" changes the shape of the code, not just its destination.
     */
    private final MeterRegistry meterRegistry;

    public CodeToolsService(FileService fileService,
                            ObjectProvider<Tracer> tracerProvider,
                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.fileService = fileService;
        this.tracer = tracerProvider.getIfAvailable();
        this.meterRegistry = meterRegistryProvider.getIfAvailable(SimpleMeterRegistry::new);
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
        return traced("read_file", path, len(path), () -> fileService.readFile(path));
    }

    @McpTool(name = "list_files",
            description = "List files and directories at a given path. "
            + "Empty path lists the project root. Entries are prefixed [DIR] or [FILE].",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list_files(
            @McpToolParam(description = "Relative path to a directory; empty = project root",
                    required = false)
            String path) {
        return traced("list_files", path, len(path), () -> fileService.listFiles(path));
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
        return traced("get_project_structure", "depth=" + depth, 0,
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
                len(query) + len(extension),
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
        // The whole file body is an ARGUMENT here — the request payload dwarfs
        // the response ("OK: File written: ..."). This is the one case where
        // measuring only the response would hide the cost entirely.
        return traced("write_file", path, len(path) + len(content),
                () -> fileService.writeFile(path, content));
    }

    @McpTool(name = "create_file",
            description = "Create a new file. Fails if it already exists — "
            + "use write_file to overwrite. Restricted to allowed extensions.")
    public String create_file(
            @McpToolParam(description = "Relative path for the new file", required = true)
            String path,
            @McpToolParam(description = "Initial file content", required = true)
            String content) {
        return traced("create_file", path, len(path) + len(content),
                () -> fileService.createFile(path, content));
    }

    @McpTool(name = "move_file",
            description = "Move or rename a file. For directories use move_directory.")
    public String move_file(
            @McpToolParam(description = "Source relative path", required = true)
            String from_path,
            @McpToolParam(description = "Destination relative path", required = true)
            String to_path) {
        return traced("move_file", from_path + " -> " + to_path,
                len(from_path) + len(to_path),
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
                len(from_path) + len(to_path),
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
        log.warn("[TOOL] delete_file is irreversible: {}", path);
        return traced("delete_file", path, len(path) + len(confirm),
                () -> fileService.deleteFile(path));
    }

    // ── Telemetry ────────────────────────────────────────────────────────

    private static int len(@Nullable String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * Wraps one tool invocation in a span and three meters, and ALWAYS returns
     * the operation's result. Errors come back as an "ERROR: ..." string rather
     * than an exception: that text is what the model reads, and a readable
     * message is more useful to it than a protocol-level error it cannot
     * inspect.
     *
     * SPAN KIND IS SERVER, NOT INTERNAL. This span handles an inbound request,
     * so SERVER is the semantically correct kind — but it is also load-bearing:
     * Tempo's metrics generator builds the service graph and the RED metrics
     * from span kind. As INTERNAL (what tracer.nextSpan() produces by default)
     * this service never appeared as a node and generated no RED metrics.
     * Hence spanBuilder() instead of nextSpan().
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
     *
     * @param requestChars total characters of the incoming arguments, measured
     *                     at the call site because the summary string omits
     *                     file bodies on purpose
     */
    private String traced(String toolName, String argsSummary, int requestChars,
                          FileOperation operation) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result;

        if (tracer == null) {
            log.info("[TOOL] {}: {}", toolName, argsSummary);
            result = execute(toolName, operation);
        } else {
            Span span = tracer.spanBuilder()
                    .name("mcp_tool " + toolName)
                    .kind(Span.Kind.SERVER)
                    .tag("gen_ai.operation.name", "execute_tool")
                    .tag("gen_ai.tool.name", toolName)
                    .tag("mcp.tool.args", String.valueOf(argsSummary))
                    .tag("mcp.tool.request.length", String.valueOf(requestChars))
                    .tag("framework", FRAMEWORK)
                    .start();
            try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
                log.info("[TOOL] {}: {}", toolName, argsSummary);
                result = execute(toolName, operation);
                span.tag("mcp.tool.response.length", String.valueOf(result.length()));
            } finally {
                span.end();
            }
        }

        record(toolName, requestChars, result, sample);
        return result;
    }

    /**
     * Three meters, named so they slice by the same `framework` label as the
     * agent modules and land on the existing Grafana panels.
     *
     * OUTCOME IS DERIVED FROM THE RESULT TEXT, not from an exception, because
     * failures never propagate out of execute() — they are returned to the
     * model as "ERROR: ...". Deriving it here keeps the error rate honest;
     * counting only thrown exceptions would report a permanent 0% error rate
     * while the model reads failures all day.
     *
     * PAYLOAD SIZE IS SPLIT BY DIRECTION, not recorded as a single "result
     * size". The two directions are wildly asymmetric per tool and that
     * asymmetry is the point: read_file sends a path and returns kilobytes,
     * while write_file sends the whole file body and returns "OK: File
     * written". Measuring only the response makes the most expensive call in
     * the server look free. The tag values mirror gen_ai_token_type=input|output
     * so the panel reads the same way as the token panels.
     *
     * These are CHARACTERS, not tokens. This server has no model and no
     * tokenizer, and MCP does not report usage back to the server, so the
     * length of the string is the only honest measure available here. The
     * token-side cost is visible only in the agent modules.
     */
    private void record(String toolName, int requestChars, String result, Timer.Sample sample) {
        String outcome = result.startsWith("ERROR:") ? "error" : "success";

        sample.stop(Timer.builder("mcp.tool.duration")
                .description("Duration of an MCP tool invocation")
                .tag("tool", toolName)
                .tag("outcome", outcome)
                .tag("framework", FRAMEWORK)
                .register(meterRegistry));

        meterRegistry.counter("mcp.tool.calls",
                "tool", toolName,
                "outcome", outcome,
                "framework", FRAMEWORK).increment();

        payloadSize(toolName, REQUEST).record(requestChars);
        payloadSize(toolName, RESPONSE).record(result.length());
    }

    private DistributionSummary payloadSize(String toolName, String direction) {
        return DistributionSummary.builder("mcp.tool.payload.size")
                .description("Characters exchanged with the model by an MCP tool")
                .baseUnit("chars")
                .tag("tool", toolName)
                .tag("direction", direction)
                .tag("framework", FRAMEWORK)
                .register(meterRegistry);
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
