package com.xkondix.multimodal.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Writes generated and uploaded artifacts to disk and hands back a URL.
 *
 * WHY A URL AND NOT THE BYTES. Everything here is large: a generated PNG is
 * around 2.5 MB, a Lyria song several more. If those bytes were returned from
 * a @Tool method they would go straight into the model's context on the next
 * turn, into the span attributes, and into Loki — three places that all break
 * in different ways, two of them silently. The tool returns a sentence; the
 * browser fetches the file separately.
 *
 * Layout: media/{yyyy-MM-dd}/{uuid}.{ext}. Dated folders because a demo run
 * produces a dozen files and a flat directory becomes unusable after a week.
 * Nothing is cleaned up automatically — the folder is in .gitignore and this
 * is a laptop, not a service.
 */
@Slf4j
@Component
public class MediaStorage {

    /** Public URL prefix — must match MultimodalController's mapping. */
    public static final String URL_PREFIX = "/api/v1/multimodal/media/";

    private final Path root;

    public MediaStorage(@Value("${multimodal.media-dir:./media}") String mediaDir) {
        this.root = Path.of(mediaDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create media dir " + this.root, e);
        }
        log.info("[MEDIA] storing artifacts under {}", this.root);
    }

    /**
     * @return the public URL of the stored file, e.g.
     *         /api/v1/multimodal/media/2026-09-05/7f3c….png
     */
    public String store(byte[] content, String extension) {
        String day = LocalDate.now().toString();
        String name = UUID.randomUUID() + "." + extension;
        try {
            Path dir = this.root.resolve(day);
            Files.createDirectories(dir);
            Files.write(dir.resolve(name), content);
            log.info("[MEDIA] wrote {}/{} ({} bytes)", day, name, content.length);
            return URL_PREFIX + day + "/" + name;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot store media " + name, e);
        }
    }

    /**
     * Everything on disk, newest first — the fallback behind the gallery's
     * "wszystko" view.
     *
     * WHY SCANNING AND NOT JUST THE INDEX. The Redis index only knows about
     * artifacts created after it existed, and only under the conversation
     * that made them. Files from earlier runs — and from every reload, since
     * the conversation id is regenerated each time the page opens — would be
     * invisible forever while sitting right there in the folder.
     *
     * What is lost by scanning: the prompt and the true kind. A .mp3 on disk
     * could be narration or a Lyria song and the filesystem cannot tell. So
     * this is a FALLBACK, merged UNDER the index rather than replacing it:
     * anything the registry knows about keeps its prompt and its real kind,
     * and only the orphans get a guess from the extension.
     */
    public List<Artifact> scanAll(int limit) {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(root, 2)) {
            return files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparingLong(MediaStorage::lastModified).reversed())
                    .limit(limit)
                    .map(this::toArtifact)
                    .toList();
        } catch (IOException e) {
            log.warn("[MEDIA] cannot scan {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    private Artifact toArtifact(Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        String name = file.getFileName().toString().toLowerCase();

        // Extension is all we have here. An mp3 is filed as SPEECH because
        // narration is far more common than music in this demo — the registry
        // overrides it whenever it actually knows.
        Artifact.Kind kind =
                name.endsWith(".mp4") ? Artifact.Kind.VIDEO
                        : name.endsWith(".mp3") ? Artifact.Kind.SPEECH
                        : name.endsWith(".txt") ? Artifact.Kind.TEXT
                        : Artifact.Kind.IMAGE;

        return new Artifact(URL_PREFIX + relative, kind, null, lastModified(file));
    }

    private static long lastModified(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    /**
     * Resolves a public URL path back to a file, refusing anything that
     * escapes the media root.
     *
     * The same path-traversal guard as claude-mcp-server's FileService, and
     * for the same reason: this segment comes from the URL, so it is user
     * input even though we generated the name ourselves.
     */
    public Path resolve(String relativePath) {
        Path resolved = this.root.resolve(relativePath).normalize();
        if (!resolved.startsWith(this.root)) {
            throw new SecurityException("Path traversal detected: " + relativePath);
        }
        return resolved;
    }
}
