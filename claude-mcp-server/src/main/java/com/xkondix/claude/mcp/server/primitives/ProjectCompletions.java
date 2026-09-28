package com.xkondix.claude.mcp.server.primitives;

import com.xkondix.claude.mcp.server.tools.FileService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpComplete;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * MCP COMPLETIONS — the fourth server-side primitive, and the only one the
 * human interacts with directly.
 *
 * ── WHY THIS IS WORTH HAVING AT ALL ────────────────────────────────────────
 *
 * Tools, resources and prompts are all consumed by the model or by the client.
 * A completion is consumed by the PERSON typing: the client sends what has
 * been typed so far and this server answers with candidates. In Claude
 * Desktop that is a dropdown, filled by a Java process over STDIO.
 *
 * It costs almost nothing and it is the most legible demonstration of MCP
 * being bidirectional rather than a request/response API for tools.
 *
 * ── ONE COMPLETION PER SINGLE-ARGUMENT TARGET, ON PURPOSE ──────────────────
 *
 * A @McpComplete method written as `List<String> complete(String prefix)`
 * receives the prefix with no indication of WHICH argument is being typed.
 * That is fine for a URI template with one variable, or a prompt with one
 * meaningful argument, and wrong for anything else: suggesting "traces, logs,
 * metrics" while the user fills in a module name is worse than suggesting
 * nothing.
 *
 * So only single-argument targets are completed here. diagnose_telemetry and
 * migrate_dependency both take two arguments and are deliberately left out —
 * completing them correctly needs the CompleteRequest.CompleteArgument
 * overload, which carries the argument name, and that is a refinement worth
 * making only if the menu turns out to be useful in practice.
 *
 * Every method filters case-insensitively and caps the list: a completion that
 * returns everything is a list, not a suggestion.
 */
@Slf4j
@Service
public class ProjectCompletions {

    private static final int MAX_SUGGESTIONS = 10;

    private final FileService fileService;

    public ProjectCompletions(FileService fileService) {
        this.fileService = fileService;
    }

    /** Completes {document} in project://docs/{document}. */
    @McpComplete(uri = "project://docs/{document}")
    public List<String> completeDocument(String prefix) {
        return filter(ProjectResources.documentNames(), prefix);
    }

    /** Completes {module} in project://module/{module}/config. */
    @McpComplete(uri = "project://module/{module}/config")
    public List<String> completeModuleUri(String prefix) {
        return filter(moduleNames(), prefix);
    }

    /** Completes the single argument of the review_module prompt. */
    @McpComplete(prompt = "review_module")
    public List<String> completeReviewModule(String prefix) {
        return filter(moduleNames(), prefix);
    }

    /**
     * Maven modules, discovered rather than listed.
     *
     * A hardcoded array would be one more place to update when a module is
     * added — and this project added one (multimodal-lab) while three
     * documents still claimed it did not exist. The directory listing cannot
     * go stale.
     *
     * The listing is small and this runs per keystroke-ish, so it is not
     * cached; if that ever shows up in mcp_primitive_duration, cache it.
     */
    private List<String> moduleNames() {
        try {
            Path root = fileService.resolveAndValidate("");
            try (Stream<Path> dirs = Files.list(root)) {
                return dirs.filter(Files::isDirectory)
                        .filter(dir -> Files.exists(dir.resolve("pom.xml")))
                        .map(dir -> dir.getFileName().toString())
                        .sorted()
                        .toList();
            }
        } catch (Exception e) {
            // A failed completion must never surface as an error to the user
            // typing — an empty dropdown is the correct degradation.
            log.debug("cannot list modules for completion: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<String> filter(List<String> candidates, String prefix) {
        String needle = prefix == null ? "" : prefix.toLowerCase();
        return candidates.stream()
                .filter(candidate -> candidate.toLowerCase().startsWith(needle))
                .limit(MAX_SUGGESTIONS)
                .toList();
    }
}
