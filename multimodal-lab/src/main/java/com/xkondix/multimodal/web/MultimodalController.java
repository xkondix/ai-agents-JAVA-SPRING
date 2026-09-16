package com.xkondix.multimodal.web;

import com.xkondix.multimodal.media.Artifact;
import com.xkondix.multimodal.media.ArtifactRegistry;
import com.xkondix.multimodal.media.MediaCollector;
import com.xkondix.multimodal.media.MediaStorage;
import com.xkondix.multimodal.service.MultimodalAgentService;
import com.xkondix.multimodal.service.TranscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * One chat endpoint that takes text, a picture and a voice note; a gallery of
 * everything produced; a route that serves the files; and two separate resets.
 *
 * MULTIPART, NOT JSON. The browser sends a MediaRecorder blob and a photo from
 * a file input; base64 in a JSON body would inflate them by a third and push
 * megabytes through the request logger.
 *
 * An uploaded image is STORED FIRST and only its URL reaches the agent. That
 * is what lets the router stay on a cheap model — it decides whether the
 * picture is worth a vision call instead of paying for one on every turn.
 *
 * TWO RESETS, ON PURPOSE. Clearing the model's memory and throwing away what
 * it made are different intentions, and merging them into one button is the
 * kind of destructive surprise a demo should not contain. Memory goes,
 * artifacts stay — they are still addressable by URL and may be open in
 * another tab.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/multimodal")
public class MultimodalController {

    private final MultimodalAgentService agent;
    private final TranscriptionService transcription;
    private final MediaStorage storage;
    private final MediaCollector collector;
    private final ArtifactRegistry gallery;

    public record ChatResponse(String conversationId, String question, String answer,
                               String uploadedImage, List<String> media) {}

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ChatResponse chat(
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(required = false) String question,
            @RequestPart(required = false) MultipartFile image,
            @RequestPart(required = false) MultipartFile audio) throws IOException {

        collector.start();
        try {
            String text = question;

            // Speech-to-text runs BEFORE the router: a voice note is the
            // question, not something the model can choose to look up.
            if (audio != null && !audio.isEmpty()) {
                String filename = audio.getOriginalFilename() == null
                        ? "recording.webm" : audio.getOriginalFilename();
                String spoken = transcription.transcribe(audio.getBytes(), filename);
                text = (text == null || text.isBlank()) ? spoken : text + "\n\n" + spoken;
            }

            // Stored, not attached — see the class comment.
            String imageUrl = null;
            if (image != null && !image.isEmpty()) {
                String ext = extensionOf(image.getOriginalFilename(), image.getContentType());
                imageUrl = storage.store(image.getBytes(), ext);
                gallery.add(conversationId,
                        Artifact.of(imageUrl, Artifact.Kind.UPLOAD, image.getOriginalFilename()));
            }

            if ((text == null || text.isBlank()) && imageUrl == null) {
                return new ChatResponse(conversationId, "",
                        "Say something, or attach a picture.", null, List.of());
            }
            if (text == null || text.isBlank()) {
                text = "Have a look at this and tell me what it is.";
            }

            String answer = agent.chat(conversationId, text, imageUrl);

            // Persist AFTER the turn: the collector holds what the tools made
            // during it, whatever order the model called them in.
            collector.collected().forEach(a -> gallery.add(conversationId, a));

            return new ChatResponse(conversationId, text, answer, imageUrl, collector.urls());

        } finally {
            // Remove the ThreadLocal rather than leaving an empty list: with a
            // reused carrier a stale entry is a slow leak that only shows up
            // under load.
            collector.clear();
        }
    }

    /**
     * The gallery.
     *
     * @param scope "all" (default) — everything this lab has ever produced,
     *              index merged with a disk scan. This is the default because
     *              the conversation id is regenerated on every page load, so
     *              scoping to the conversation would hide yesterday's work and
     *              make the panel look broken after a refresh.
     *              "conversation" — only what this thread produced.
     */
    @GetMapping("/artifacts")
    public List<Artifact> artifacts(
            @RequestParam(defaultValue = "default") String conversationId,
            @RequestParam(defaultValue = "all") String scope) {
        return "conversation".equals(scope)
                ? gallery.list(conversationId)
                : gallery.listAll();
    }

    /** Clears the Redis-backed thread. The gallery and the files are kept. */
    @DeleteMapping("/memory/{conversationId}")
    public ResponseEntity<Void> resetMemory(@PathVariable String conversationId) {
        agent.reset(conversationId);
        return ResponseEntity.noContent().build();
    }

    /** Clears one conversation's gallery index. Files stay on disk. */
    @DeleteMapping("/artifacts/{conversationId}")
    public ResponseEntity<Void> resetGallery(@PathVariable String conversationId) {
        gallery.clear(conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves stored artifacts. The path can be one or more segments deep —
     * the layout is media/{day}/{uuid}.{ext}, but a disk scan may surface
     * anything already in the folder. Path traversal is rejected in
     * MediaStorage, not here.
     */
    @GetMapping("/media/**")
    public ResponseEntity<Resource> media(jakarta.servlet.http.HttpServletRequest request) {
        String full = request.getRequestURI();
        String relative = full.substring(full.indexOf("/media/") + "/media/".length());

        Path file = storage.resolve(relative);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        String name = file.getFileName().toString().toLowerCase();
        MediaType type = name.endsWith(".mp3") ? MediaType.parseMediaType("audio/mpeg")
                : name.endsWith(".mp4") ? MediaType.parseMediaType("video/mp4")
                : name.endsWith(".txt") ? MediaType.parseMediaType("text/plain; charset=UTF-8")
                : name.endsWith(".jpg") || name.endsWith(".jpeg") ? MediaType.IMAGE_JPEG
                : MediaType.IMAGE_PNG;

        return ResponseEntity.ok()
                .contentType(type)
                // Inline: the browser shows the picture and plays the audio in
                // the conversation instead of downloading them.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + file.getFileName() + "\"")
                .body(new FileSystemResource(file));
    }

    private static String extensionOf(String filename, String contentType) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase();
            if (ext.matches("png|jpg|jpeg|webp|gif")) {
                return ext.equals("jpeg") ? "jpg" : ext;
            }
        }
        return contentType != null && contentType.contains("png") ? "png" : "jpg";
    }
}
