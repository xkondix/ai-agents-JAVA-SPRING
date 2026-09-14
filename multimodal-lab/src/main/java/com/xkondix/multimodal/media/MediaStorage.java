package com.xkondix.multimodal.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Writes generated and uploaded artifacts to disk and hands back a URL.
 *
 * WHY A URL AND NOT THE BYTES. Everything here is large: a generated PNG is
 * around 1.5 MB, a minute of speech a few hundred kB. If those bytes were
 * returned from a @Tool method they would go straight into the model's
 * context on the next turn, into the span attributes, and into Loki — three
 * places that all break in different ways, two of them silently. The tool
 * returns a sentence with a path; the browser fetches the file separately.
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
