package com.xkondix.claude.mcp.server.tools;

import com.xkondix.claude.mcp.server.config.ClaudeMcpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;

/**
 * File operations for the MCP tools, sandboxed by ClaudeMcpProperties:
 * every path is resolved against projectRoot and rejected if it escapes it,
 * reads/writes/moves/deletes are limited to allowedExtensions, and paths
 * inside ignoredDirs (.git, target, ...) are not reachable at all.
 *
 * ClaudeMcpProperties is a record, so accessors are projectRoot() /
 * allowedExtensions() / ignoredDirs() — no get* prefixes.
 *
 * THE SANDBOX HAS TO CLOSE FROM EVERY SIDE. An earlier version checked the
 * extension allow-list only on read/write/create. That left two doors open:
 *   1. move_file .env notes.txt  →  read_file notes.txt   (rename around the
 *      allow-list, then read),
 *   2. move_directory .git elsewhere / delete_file of any extension —
 *      ignoredDirs only guarded the tree WALK, never a direct path.
 * guard() below is the single place all of that is decided, and every
 * operation goes through it for every path it touches.
 *
 * SYMLINKS. resolveAndValidate() normalizes lexically AND, when the path
 * exists, compares real paths: a link inside the project that points outside
 * of it would otherwise pass the startsWith() check and be followed.
 *
 * Two error styles on purpose: a path that escapes the sandbox throws
 * SecurityException (a programming/abuse signal that must not be mistaken for
 * data), while everyday problems — missing file, wrong extension — return an
 * "ERROR: ..." string the model can read and act on.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileService {

    /** Upper bound for files read whole into memory (read_file, search_in_files). */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024;

    private final ClaudeMcpProperties props;

    private Path root() {
        return Path.of(props.projectRoot()).toAbsolutePath().normalize();
    }

    public Path resolveAndValidate(String relativePath) {
        Path root = root();
        Path resolved = root.resolve(relativePath == null ? "" : relativePath).normalize();
        if (!resolved.startsWith(root)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        // Lexical check passed; now the physical one. A symlink inside the
        // project pointing outside would survive normalize() — toRealPath()
        // follows it. Only possible for paths that exist; new files are
        // covered by the check on their (existing) parent.
        Path probe = Files.exists(resolved) ? resolved : resolved.getParent();
        if (probe != null && Files.exists(probe)) {
            try {
                if (!probe.toRealPath().startsWith(root.toRealPath())) {
                    throw new SecurityException("Path escapes the sandbox via link: " + relativePath);
                }
            } catch (IOException e) {
                throw new SecurityException("Cannot verify path: " + relativePath);
            }
        }
        return resolved;
    }

    public boolean isAllowedExtension(String path) {
        String lower = path.toLowerCase();
        return props.allowedExtensions().stream().anyMatch(lower::endsWith);
    }

    /** True when any segment of the RELATIVE path is an ignored directory. */
    private boolean isInIgnoredDir(Path resolved) {
        Path rel = root().relativize(resolved);
        for (Path segment : rel) {
            if (props.ignoredDirs().contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The one gate every operation passes: ignored dirs, then extension.
     * (The sandbox escape check already happened in resolveAndValidate.)
     *
     * @param requireExtension false for directory operations, true for files
     * @return null when the path is fine, otherwise the "ERROR: ..." text
     */
    private String guard(String relativePath, Path resolved, boolean requireExtension) {
        if (isInIgnoredDir(resolved)) {
            return "ERROR: Path is inside an ignored directory: " + relativePath;
        }
        if (requireExtension && !isAllowedExtension(relativePath)) {
            return "ERROR: File type not allowed: " + relativePath;
        }
        return null;
    }

    public String readFile(String relativePath) throws IOException {
        Path path = resolveAndValidate(relativePath);
        String denied = guard(relativePath, path, true);
        if (denied != null) return denied;
        if (!Files.exists(path)) return "ERROR: File not found: " + relativePath;
        if (Files.size(path) > MAX_READ_BYTES) {
            return "ERROR: File too large to read (" + Files.size(path) + " bytes): " + relativePath;
        }
        log.info("read_file: {}", relativePath);
        return Files.readString(path);
    }

    public String writeFile(String relativePath, String content) throws IOException {
        Path path = resolveAndValidate(relativePath);
        String denied = guard(relativePath, path, true);
        if (denied != null) return denied;
        Files.writeString(path, content);
        log.info("write_file: {} ({} chars)", relativePath, content.length());
        return "OK: File written: " + relativePath;
    }

    public String createFile(String relativePath, String content) throws IOException {
        Path path = resolveAndValidate(relativePath);
        String denied = guard(relativePath, path, true);
        if (denied != null) return denied;
        if (Files.exists(path)) return "ERROR: File already exists. Use write_file to overwrite.";
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        log.info("create_file: {}", relativePath);
        return "OK: File created: " + relativePath;
    }

    public String deleteFile(String relativePath) throws IOException {
        Path path = resolveAndValidate(relativePath);
        if (Files.isDirectory(path)) return "ERROR: Not a file: " + relativePath;
        String denied = guard(relativePath, path, true);
        if (denied != null) return denied;
        if (!Files.exists(path)) return "ERROR: File not found: " + relativePath;
        Files.delete(path);
        log.warn("delete_file: {}", relativePath);
        return "OK: File deleted: " + relativePath;
    }

    public String moveFile(String from, String to) throws IOException {
        Path fromPath = resolveAndValidate(from);
        Path toPath   = resolveAndValidate(to);
        // Shape check first so a directory gets the helpful redirect, not an
        // extension complaint; the allow-list then applies to BOTH ends —
        // renaming .env to notes.txt must not become a way to read it.
        if (Files.isDirectory(fromPath)) return "ERROR: Use move_directory for directories.";
        String denied = guard(from, fromPath, true);
        if (denied == null) denied = guard(to, toPath, true);
        if (denied != null) return denied;
        if (!Files.exists(fromPath)) return "ERROR: Source not found: " + from;
        Files.createDirectories(toPath.getParent());
        Files.move(fromPath, toPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("move_file: {} -> {}", from, to);
        return "OK: Moved " + from + " -> " + to;
    }

    /**
     * Moves or renames a directory recursively.
     * Uses Files.move() which is atomic on same filesystem (rename).
     * Falls back to copy-then-delete for cross-filesystem moves.
     */
    public String moveDirectory(String from, String to) throws IOException {
        Path fromPath = resolveAndValidate(from);
        Path toPath   = resolveAndValidate(to);
        String denied = guard(from, fromPath, false);
        if (denied == null) denied = guard(to, toPath, false);
        if (denied != null) return denied;
        if (fromPath.equals(root())) return "ERROR: Cannot move the project root.";
        if (!Files.exists(fromPath))    return "ERROR: Source not found: " + from;
        if (!Files.isDirectory(fromPath)) return "ERROR: Source is not a directory: " + from;
        if (Files.exists(toPath))       return "ERROR: Destination already exists: " + to;
        try {
            // Try atomic rename first (same filesystem)
            Files.move(fromPath, toPath, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Cross-filesystem: copy recursively then delete source
            copyDirectory(fromPath, toPath);
            deleteDirectory(fromPath);
        }
        log.warn("move_directory: {} -> {}", from, to);
        return "OK: Directory moved: " + from + " -> " + to;
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a)
                    throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir)));
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                    throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)),
                        StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            public FileVisitResult visitFile(Path file, BasicFileAttributes a)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path d, IOException e)
                    throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public String listFiles(String relativePath) throws IOException {
        Path path = (relativePath == null || relativePath.isBlank())
                ? root()
                : resolveAndValidate(relativePath);
        String denied = guard(relativePath, path, false);
        if (denied != null) return denied;
        if (!Files.exists(path)) return "ERROR: Directory not found: " + relativePath;
        StringBuilder sb = new StringBuilder("Contents of: ")
                .append(relativePath == null ? "" : relativePath).append("\n");
        try (Stream<Path> stream = Files.list(path)) {
            stream.sorted().forEach(p -> {
                String name = p.getFileName().toString();
                sb.append(Files.isDirectory(p) ? "[DIR]  " : "[FILE] ")
                  .append(name).append("\n");
            });
        }
        return sb.toString();
    }

    public String getProjectStructure(int maxDepth) throws IOException {
        Path root = root();
        StringBuilder sb = new StringBuilder("Project: ")
                .append(root.getFileName()).append("\n");
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            int depth = 0;
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (props.ignoredDirs().contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                if (depth > maxDepth) return FileVisitResult.SKIP_SUBTREE;
                sb.append("  ".repeat(depth)).append("+ ")
                  .append(dir.getFileName()).append("/\n");
                depth++;
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                depth--; return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (isAllowedExtension(file.getFileName().toString()))
                    sb.append("  ".repeat(depth)).append("- ")
                      .append(file.getFileName()).append("\n");
                return FileVisitResult.CONTINUE;
            }
        });
        return sb.toString();
    }

    public String searchInFiles(String query, String extension) throws IOException {
        Path root = root();
        StringBuilder sb = new StringBuilder("Search: \"").append(query).append("\"\n\n");
        int[] count = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes a) {
                if (props.ignoredDirs().contains(dir.getFileName().toString()))
                    return FileVisitResult.SKIP_SUBTREE;
                return FileVisitResult.CONTINUE;
            }
            public FileVisitResult visitFile(Path file, BasicFileAttributes a) throws IOException {
                String name = file.getFileName().toString();
                if (extension != null && !name.endsWith(extension)) return FileVisitResult.CONTINUE;
                if (!isAllowedExtension(name)) return FileVisitResult.CONTINUE;
                if (a.size() > MAX_READ_BYTES) return FileVisitResult.CONTINUE; // skip, don't OOM
                String content = Files.readString(file);
                if (content.toLowerCase().contains(query.toLowerCase())) {
                    String rel = root.relativize(file).toString();
                    sb.append("FILE: ").append(rel).append("\n");
                    String[] lines = content.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].toLowerCase().contains(query.toLowerCase()))
                            sb.append("  L").append(i+1).append(": ").append(lines[i].trim()).append("\n");
                    }
                    sb.append("\n");
                    count[0]++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        sb.append("Total: ").append(count[0]).append(" file(s) matched");
        return sb.toString();
    }
}
