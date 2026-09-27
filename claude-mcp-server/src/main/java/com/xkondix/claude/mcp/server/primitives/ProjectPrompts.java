package com.xkondix.claude.mcp.server.primitives;

import org.springframework.ai.mcp.annotation.McpArg;
import org.springframework.ai.mcp.annotation.McpPrompt;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * MCP PROMPTS — prefilled instructions the CLIENT can hand to its model.
 *
 * ── WHAT THESE ARE, AND WHAT THEY ARE NOT ──────────────────────────────────
 *
 * A prompt is not a tool and does not execute anything. The client fetches
 * rendered text and gives it to its own model; this server never sees what
 * happens next. In Claude Desktop they show up as a menu the human picks
 * from, followed by a form built entirely from the annotations below — the
 * field label comes from @McpArg(name), the placeholder from its description,
 * and the red asterisk from required = true.
 *
 * They are the closest MCP equivalent of a "skill": an instruction the model
 * follows, supplied by the server rather than installed in the client. The
 * important difference from a skill is ownership — these ship with the
 * project, version with it, and work in any MCP client, not just one vendor's.
 *
 * ── WHAT IS WORTH PUTTING IN ONE ───────────────────────────────────────────
 *
 * Not "write a test" or "refactor this" — a model does that without help and
 * the prompt only adds noise. Not a description of the architecture either;
 * README.md and ENDPOINTS.md already carry that, and a second copy drifts.
 *
 * The prompts below all pass one test: THEY ENCODE KNOWLEDGE WHOSE OMISSION
 * PRODUCES NO ERROR. Every step in them exists because something once went
 * wrong quietly — a green build that exported no telemetry, a tool whose
 * arguments all arrived as null, a property rename that bound to nothing.
 * That is exactly the material a model cannot infer and a human forgets in
 * three weeks.
 *
 * ── A PROMPT IS STILL ONLY AN INSTRUCTION ──────────────────────────────────
 *
 * Nothing here is enforced. A model may follow these steps loosely or skip
 * one, and no layer will object — the same limitation as a skill, and the
 * same one this project keeps running into elsewhere ("an instruction is not
 * a contract"). Where a rule must hold, it belongs in a test:
 * ToolSchemaContractTest is what actually stops the arg0 regression, and the
 * prompt below merely reminds you to extend it.
 *
 * ── WHY RENDERING IS INSTRUMENTED AT ALL ───────────────────────────────────
 *
 * Building a string needs no telemetry — these methods touch no files, make
 * no calls and cannot fail in any interesting way. The span is not about
 * latency; it answers a question nothing else in the stack can: DID ANYONE
 * EVER USE THIS?
 *
 * A prompt that is never picked is dead weight that still costs a menu entry
 * and a maintenance obligation, and without a signal here the only evidence
 * would be asking people. mcp_primitive_calls{primitive="prompt"} turns that
 * into a number. The same argument applies to resources, which is why both go
 * through the same wrapper.
 *
 * Returning String rather than GetPromptResult: a single message is all these
 * need, and the annotation accepts both. GetPromptResult would be required
 * for a multi-turn prompt or to set the role explicitly.
 */
@Service
public class ProjectPrompts {

    private final McpPrimitiveTelemetry telemetry;

    public ProjectPrompts(McpPrimitiveTelemetry telemetry) {
        this.telemetry = telemetry;
    }

    /** Values a completion provider can offer for the `signal` argument. */
    static List<String> signals() {
        return List.of("traces", "logs", "metrics");
    }

    @McpPrompt(
            name = "migrate_dependency",
            title = "Migrate a dependency in this project",
            description = "Checklist for bumping Spring Boot, Spring AI or LangChain4j "
                    + "in ai-agents-JAVA-SPRING, written from the failures of the last one.")
    public String migrateDependency(
            @McpArg(name = "dependency",
                    description = "What is being upgraded, e.g. Spring AI or LangChain4j",
                    required = true) String dependency,
            @McpArg(name = "target_version",
                    description = "Target version, e.g. 2.1.0",
                    required = false) String targetVersion) {

        return telemetry.traced("prompt", "migrate_dependency", () -> {
            String target = (targetVersion == null || targetVersion.isBlank())
                    ? "the next version" : targetVersion;

            return """
                Upgrade %s to %s in the ai-agents-JAVA-SPRING project.

                This project was migrated from Spring Boot 3.5 / Spring AI 1.1.4 once
                already. Every serious problem in that migration produced a GREEN BUILD
                and a started application. Work through the steps in order and do not
                treat a successful compile as evidence of anything.

                1. VERSION, IN THE RIGHT PLACE
                   Versions live in the root pom.xml properties and are applied through
                   BOM imports. Never add a <version> to an individual module.
                   claude-mcp-server is OUTSIDE the reactor with its own parent — a root
                   `mvn clean install` does not rebuild it. Check it separately.

                2. THE ARTIFACT ID MAY CHANGE WITHOUT THE VERSION
                   LangChain4j ships two parallel starter families at the SAME version,
                   distinguished only by artifactId (-spring-boot-starter for Boot 3,
                   -spring-boot4-starter for Boot 4). Renames are also normal across a
                   major: spring-ai-advisors-vector-store became
                   spring-ai-vector-store-advisor in 2.0.
                   Check the artifact names, not only the numbers.

                3. PROPERTY NAMES — VERIFY IN THE SOURCES, NOT IN A BLOG
                   An unknown property binds to nothing and logs nothing. In the last
                   migration `management.otlp.tracing.endpoint` kept working as a
                   config key and stopped working as a feature.
                   For every property you touch, find the @ConfigurationProperties class
                   or the release notes. Not a tutorial.

                4. MODEL AND CATALOGUE NAMES ARE NOT DOCUMENTATION
                   If the change touches a provider, verify model ids against the
                   provider's live catalogue endpoint. OpenRouter's own documentation
                   uses a TTS model slug that does not exist in its catalogue, including
                   in the "Model not found?" troubleshooting section.

                5. BUILD, THEN VERIFY THE THINGS A BUILD CANNOT SEE
                   - mvn clean install at the root, then in claude-mcp-server
                   - start a module and confirm telemetry still leaves it: the Preflight
                     row of the Grafana dashboard, not just the absence of errors
                   - confirm tools still bind their arguments (an argument arriving as
                     null is silent; ToolSchemaContractTest catches the schema side)
                   - confirm MCP still connects: a transport mismatch does not degrade
                     an agent, it stops it from starting, and the error reads like a
                     network problem ("Did not observe any item or terminal signal")

                6. WRITE DOWN WHAT BIT YOU
                   OBSERVABILITY.md has a table of silent failures. If this upgrade
                   produced a new one, add a row. That table is the most reused artifact
                   in the repository.

                Report what you changed, what you verified and what you could not verify.
                """.formatted(dependency, target);
        });
    }

    @McpPrompt(
            name = "add_mcp_tool",
            title = "Add a tool to an MCP server in this project",
            description = "The full checklist for adding an @McpTool method, including "
                    + "the three things that fail silently.")
    public String addMcpTool(
            @McpArg(name = "tool_name",
                    description = "Wire name of the new tool, e.g. count_lines",
                    required = true) String toolName,
            @McpArg(name = "purpose",
                    description = "What the tool should do",
                    required = false) String purpose) {

        return telemetry.traced("prompt", "add_mcp_tool", () -> {
            String what = (purpose == null || purpose.isBlank())
                    ? "(describe the purpose before writing code)" : purpose;

            return """
                Add an MCP tool named `%s` to this project. Purpose: %s

                Follow the conventions already in place — they exist because of
                regressions that were invisible when they happened.

                1. DECLARE THE NAME EXPLICITLY
                   @McpTool(name = "%s", description = "...")
                   Spring AI can derive the name from the method, and that makes the
                   wire contract a side effect of a refactor: rename the method in an
                   IDE and every client silently loses the tool.

                2. DESCRIBE EVERY PARAMETER
                   @McpToolParam(description = "...", required = true|false)
                   There is NO `name` attribute on that annotation. Parameter names in
                   the generated JSON schema come from reflection metadata and nothing
                   else, which means the -parameters compiler flag. Without it the
                   schema publishes arg0/arg1, every argument arrives as null, and
                   there is no error anywhere. That regression happened three times.

                3. OPTIONAL ARGUMENTS MUST BE BOXED
                   Use Integer, not int. An absent optional argument arrives as null and
                   a primitive fails in reflection before the method body runs.

                4. SET THE HINTS HONESTLY
                   annotations = @McpTool.McpAnnotations(readOnlyHint = ...,
                   destructiveHint = ...). Clients use these to decide what to confirm
                   with the user.

                5. WRAP IT IN THE TELEMETRY HELPER
                   In claude-mcp-server: CodeToolsService.traced(...). In mcp-server:
                   McpToolTelemetry from `common`. Span names, tag names and meter names
                   must stay identical across both — the Grafana panels are shared.
                   Measure the REQUEST size too, not only the response: write_file
                   carries a whole file body in and returns "OK", so response-only
                   measurement makes the most expensive call look free.

                6. EXTEND THE CONTRACT TEST
                   ToolSchemaContractTest asserts the exact set of tool names and that
                   no parameter resolves to argN. A new tool that is not in that set
                   fails the build — on purpose.

                7. IF THE TOOL IS DESTRUCTIVE
                   In mcp-server, gate it with HumanApprovalService.gate(...) from
                   `common`. In claude-mcp-server this is NOT possible: STDIO has no
                   second channel for a human decision and blocking the JSON-RPC thread
                   deadlocks the stream. There, use an explicit confirmation argument
                   instead, as delete_file does.
                """.formatted(toolName, what, toolName);
        });
    }

    @McpPrompt(
            name = "diagnose_telemetry",
            title = "Diagnose a missing telemetry signal",
            description = "Ordered checklist for traces, logs or metrics not arriving in "
                    + "Grafana — in the order that finds the cause fastest.")
    public String diagnoseTelemetry(
            @McpArg(name = "signal",
                    description = "Which signal is missing: traces, logs or metrics",
                    required = true) String signal,
            @McpArg(name = "module",
                    description = "Module name, e.g. multimodal-lab",
                    required = false) String module) {

        return telemetry.traced("prompt", "diagnose_telemetry", () -> {
            String where = (module == null || module.isBlank()) ? "the affected module" : module;
            String specific = switch (signal == null ? "" : signal.toLowerCase()) {
                case "logs" -> """
                    LOGS specifically:
                      - spring-boot-starter-actuator MUST be on the classpath. Not for
                        endpoints: ConditionalOnEnabledLoggingExport is an actuator class
                        and is the FIRST condition of OtlpLoggingAutoConfiguration. Without
                        actuator, log export backs off silently while traces and metrics
                        keep working.
                      - logback-spring.xml must declare the OpenTelemetryAppender AND
                        something must call OpenTelemetryAppender.install(openTelemetry).
                        Until install() runs the appender buffers 1000 records and drops
                        the rest, with no error.
                      - the property is management.opentelemetry.logging.export.otlp.endpoint
                      - log export is batched: wait ~10 s before concluding anything
                    """;
                case "metrics" -> """
                    METRICS specifically:
                      - the property is management.otlp.metrics.export.url — a DIFFERENT
                        prefix from traces on purpose, because it configures a Micrometer
                        registry rather than the OTel SDK
                      - metrics are PUSHED every 60 s. Nothing scrapes the application.
                        A Grafana rate() query needs interval: 1m and the datasource needs
                        timeInterval: 60s, or panels look empty at short ranges.
                      - a counter created on first use is born non-zero, so the first
                        request after a restart is invisible to rate(). Meters are
                        pre-registered at 0 in raw-agent and the LangChain4j modules;
                        Spring AI modules need a warm-up request.
                      - no *_max series survives the OTLP path. Use sum/count.
                      - a timer with no buckets has only le="+Inf" and histogram_quantile
                        returns nothing — see the MeterFilter in ObservabilityAutoConfiguration
                    """;
                default -> """
                    TRACES specifically:
                      - the property is management.opentelemetry.tracing.export.otlp.endpoint.
                        The Boot 3 spelling (management.otlp.tracing.endpoint) binds to
                        nothing and is ignored in silence.
                      - if spans exist but the trace stops at a process boundary, that is
                        propagation, not export. spring-ai-agent-mcp injects W3C context
                        into MCP calls; langchain4j-agent-mcp deliberately does not.
                      - if a service never appears in the service graph, check span KIND:
                        Tempo builds the graph and RED metrics from SERVER spans, and
                        tracer.nextSpan() produces INTERNAL.
                      - a ChatClient built with the static ChatClient.builder(chatModel)
                        gets ObservationRegistry.NOOP and produces no spans at all. Always
                        inject ChatClient.Builder.
                    """;
            };

            return """
                The %s signal is missing for %s. Diagnose it.

                Check in this order — it is sorted by how often each one was the cause:

                1. IS THE STACK RUNNING? `docker compose up -d`. The exporter logs
                   "Failed to connect to localhost/[0:0:0:0:0:0:0:1]:4318" when it is not,
                   and that message also reveals the IPv6-first resolution that makes a
                   healthy Docker container look broken. Prefer 127.0.0.1 over localhost.

                2. IS THE PROPERTY SPELLED THE BOOT 4 WAY? See below. An unknown property
                   is a surplus property: it binds to nothing and nothing complains.

                3. DOES THE SIGNAL EXIST AT THE SOURCE? Check %s's own log for the
                   exporter starting. Root logging at WARN hides those lines — io.micrometer
                   and io.opentelemetry are set to INFO in this project for that reason.

                4. IS IT IN THE BACKEND BUT NOT ON THE PANEL? Query the raw name in
                   Prometheus or the bare stream selector in Loki BEFORE adding filters.
                   "No data" for a filtered and an unfiltered query are different diagnoses.

                %s
                Report which step found it, and if it is a new failure mode add a row to
                the table in OBSERVABILITY.md.
                """.formatted(signal, where, where, specific);
        });
    }

    @McpPrompt(
            name = "review_module",
            title = "Review a module against this project's conventions",
            description = "Structured review of one module: observability wiring, tool "
                    + "contracts, configuration and comments.")
    public String reviewModule(
            @McpArg(name = "module",
                    description = "Module to review, e.g. spring-ai-agent-mcp",
                    required = true) String module) {

        return telemetry.traced("prompt", "review_module", () -> """
                Review the module `%s` against the conventions of ai-agents-JAVA-SPRING.

                Read project://docs/observability and project://module/%s/config first;
                do not review from memory of how Spring AI usually works.

                OBSERVABILITY
                  - are all three OTLP endpoints configured with the Boot 4 property names
                  - is spring-boot-starter-actuator present (required for LOG export)
                  - is every ChatClient built from the INJECTED ChatClient.Builder
                  - do hand-written spans use SERVER kind where they handle inbound calls
                  - are meter names identical to the other modules' (panels are shared)

                TOOLS AND CONTRACTS
                  - are @McpTool / @Tool names declared explicitly
                  - does every parameter carry a description, and are optional ones boxed
                  - is there a contract test asserting the tool set and the absence of argN

                CONFIGURATION
                  - are secrets read from environment variables only — this repo is public
                  - do comments in application.yml explain WHY, and are they still true
                  - is anything in the yml describing a version or a service that is gone

                COMMENTS AND DOCS
                  - flag comments that describe the pre-migration state (Spring AI 1.x,
                    Boot 3 properties, SSE transport, ports that no longer exist)
                  - flag any claim you cannot verify in the code you just read

                Report findings ordered by risk, and separate "I checked this" from
                "I suspect this". Do not propose rewriting the architecture: this is a
                teaching project with a fixed shape.
                """.formatted(module, module));
    }
}
