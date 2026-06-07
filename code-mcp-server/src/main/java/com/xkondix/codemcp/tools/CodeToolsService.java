package com.xkondix.codemcp.tools;

import com.xkondix.codemcp.approval.ApprovalService;
import com.xkondix.codemcp.approval.ApprovalType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

/**
 * Narzedzia MCP do edycji kodu — Spring AI @Tool approach.
 *
 * Spring AI automatycznie:
 *   - generuje JSON Schema z parametrow metody
 *   - rejestruje metody jako narzedzia MCP
 *   - przekazuje wywolania do odpowiednich metod
 *
 * Autoconfiguracja rejestruje ten @Service jako ToolCallbackProvider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeToolsService {

    private final FileService fileService;
    private final ApprovalService approvalService;

    // ── BEZPIECZNE — bez approval ────────────────────────────────────────

    @Tool(description = """
            Read the content of a file in the ai-agents-JAVA-SPRING project.
            Path must be relative to project root.
            Example: langchain4j-agent/src/main/resources/application.yml
            Returns full file content as text.
            """)
    public String read_file(
            @ToolParam(description = "Relative path from project root") String path) {
        log.info("[TOOL] read_file: {}", path);
        try {
            return fileService.readFile(path);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            List files and directories at a given path in the project.
            Leave path empty to list project root.
            Returns [DIR] or [FILE] prefix for each entry.
            """)
    public String list_files(
            @ToolParam(description = "Relative path to directory. Empty = project root") String path) {
        log.info("[TOOL] list_files: {}", path);
        try {
            return fileService.listFiles(path);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Get the directory tree of the ai-agents-JAVA-SPRING project.
            Ignores: .git, target, node_modules, .idea, __pycache__.
            Shows only allowed file extensions (java, xml, yml, md, json, py, etc).
            """)
    public String get_project_structure(
            @ToolParam(description = "Max directory depth (default 4)") int max_depth) {
        int depth = max_depth <= 0 ? 4 : max_depth;
        log.info("[TOOL] get_project_structure depth={}", depth);
        try {
            return fileService.getProjectStructure(depth);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            Search for text in all project files (case-insensitive).
            Returns file paths and matching lines with line numbers.
            Optionally filter by file extension.
            """)
    public String search_in_files(
            @ToolParam(description = "Text to search for") String query,
            @ToolParam(description = "Optional file extension filter, e.g. .java or .yml") String extension) {
        log.info("[TOOL] search_in_files: query={} ext={}", query, extension);
        try {
            String ext = (extension == null || extension.isBlank()) ? null : extension;
            return fileService.searchInFiles(query, ext);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ── WYMAGAJA ZATWIERDZENIA ────────────────────────────────────────────

    @Tool(description = """
            REQUIRES HUMAN APPROVAL.
            Overwrite an existing file with new content.
            The operation pauses until approved at POST /approvals/{id}/approve
            Use create_file for new files.
            """)
    public String write_file(
            @ToolParam(description = "Relative path to file") String path,
            @ToolParam(description = "Full new content of the file") String content) {
        log.warn("[TOOL] write_file PENDING APPROVAL: {}", path);
        String preview = content.length() > 300 ? content.substring(0, 300) + "..." : content;
        boolean approved = approvalService.requestApproval(
                ApprovalType.WRITE_FILE, "write_file",
                "Overwrite file: " + path,
                "PATH: " + path + "\nPREVIEW:\n" + preview);
        if (!approved) return "REJECTED: Operation cancelled.";
        try {
            return fileService.writeFile(path, content);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            REQUIRES HUMAN APPROVAL.
            Create a new file with given content.
            Fails if file already exists — use write_file to overwrite.
            The operation pauses until approved at POST /approvals/{id}/approve
            """)
    public String create_file(
            @ToolParam(description = "Relative path for the new file") String path,
            @ToolParam(description = "Initial file content") String content) {
        log.warn("[TOOL] create_file PENDING APPROVAL: {}", path);
        String preview = content.length() > 200 ? content.substring(0, 200) + "..." : content;
        boolean approved = approvalService.requestApproval(
                ApprovalType.CREATE_FILE, "create_file",
                "Create new file: " + path,
                "PATH: " + path + "\nCONTENT:\n" + preview);
        if (!approved) return "REJECTED: Operation cancelled.";
        try {
            return fileService.createFile(path, content);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    @Tool(description = """
            REQUIRES HUMAN APPROVAL.
            Move or rename a file within the project.
            The operation pauses until approved at POST /approvals/{id}/approve
            """)
    public String move_file(
            @ToolParam(description = "Source relative path") String from_path,
            @ToolParam(description = "Destination relative path") String to_path) {
        log.warn("[TOOL] move_file PENDING APPROVAL: {} -> {}", from_path, to_path);
        boolean approved = approvalService.requestApproval(
                ApprovalType.MOVE_FILE, "move_file",
                "Move: " + from_path + " -> " + to_path,
                "FROM: " + from_path + "\nTO: " + to_path);
        if (!approved) return "REJECTED: Operation cancelled.";
        try {
            return fileService.moveFile(from_path, to_path);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    // ── DOUBLE CONFIRMATION ───────────────────────────────────────────────
    @Tool(description = """
            REQUIRES DOUBLE HUMAN APPROVAL. THIS ACTION CANNOT BE UNDONE.
            Permanently delete a file from the project.
            The confirm parameter must be exactly the string: DELETE
            Requires two separate approvals at POST /approvals/{id}/approve
            """)
    public String delete_file(
            @ToolParam(description = "Relative path to file to delete") String path,
            @ToolParam(description = "Must be exactly: DELETE") String confirm) {
        if (!"DELETE".equals(confirm)) {
            return "ERROR: confirm field must be exactly: DELETE";
        }
        log.warn("[TOOL] delete_file PENDING APPROVAL (1/2): {}", path);
        boolean first = approvalService.requestApproval(
                ApprovalType.DELETE_FILE, "delete_file",
                "[1/2] DELETE: " + path,
                "PATH: " + path + "\nThis is the FIRST of TWO required approvals.");
        if (!first) return "REJECTED: Delete cancelled at first confirmation.";

        log.warn("[TOOL] delete_file PENDING APPROVAL (2/2): {}", path);
        boolean second = approvalService.requestApproval(
                ApprovalType.DELETE_FILE, "delete_file",
                "[2/2] FINAL CONFIRM DELETE: " + path,
                "PATH: " + path + "\nFINAL confirmation. File will be PERMANENTLY deleted.");
        if (!second) return "REJECTED: Delete cancelled at final confirmation.";

        try {
            return fileService.deleteFile(path);
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
