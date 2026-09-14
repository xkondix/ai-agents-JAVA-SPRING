package com.xkondix.multimodal.web;

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
 * One chat endpoint that takes text, a picture and a voice note, a route that
 * serves everything produced, and a reset for the conversation.
 *
 * MULTIPART, NOT JSON. The browser sends a MediaRecorder blob and a photo from
 * a file input; base64 in a JSON body would inflate them by a third and push
 * megabytes through the request logger.
 *
 * An uploaded image is STORED FIRST and only its URL reaches the agent. That
 * is what lets the router stay on a cheap model — it decides whether the
 * picture is worth a vision call instead of paying for one on every turn.
 *
 * THE MEDIA LIST COMES FROM MediaCollector, NOT FROM THE ANSWER TEXT. The
 * first version asked the model to repeat each URL and scraped the reply; the
 * model promptly rewrote one as a markdown link to an invented host and the UI
 * went blank while the file sat on disk. The regex remains only as a fallback
 * for an upload the user attached in this turn.
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
            }

            if ((text == null || text.isBlank()) && imageUrl == null) {
                return new ChatResponse(conversationId, "",
                        "Say something, or attach a picture.", null, List.of());
            }
            if (text == null || text.isBlank()) {
                text = "Have a look at this and tell me what it is.";
            }

            String answer = agent.chat(conversationId, text, imageUrl);
            return new ChatResponse(conversationId, text, answer, imageUrl,
                    collector.collected());

        } finally {
            // Remove the ThreadLocal rather than leaving an empty list: with a
            // reused carrier a stale entry is a slow leak that only shows up
            // under load.
            collector.clear();
        }
    }

    /** Clears the Redis-backed thread. Generated files are left on disk. */
    @DeleteMapping("/memory/{conversationId}")
    public ResponseEntity<Void> reset(@PathVariable String conversationId) {
        agent.reset(conversationId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Serves stored artifacts. The {day}/{name} split matches MediaStorage's
     * layout; path traversal is rejected there, not here.
     */
    @GetMapping("/media/{day}/{name}")
    public ResponseEntity<Resource> media(@PathVariable String day, @PathVariable String name) {
        Path file = storage.resolve(day + "/" + name);
        if (!Files.exists(file)) {
            return ResponseEntity.notFound().build();
        }
        MediaType type = name.endsWith(".mp3") ? MediaType.parseMediaType("audio/mpeg")
                : name.endsWith(".jpg") ? MediaType.IMAGE_JPEG
                : MediaType.IMAGE_PNG;

        return ResponseEntity.ok()
                .contentType(type)
                // Inline: the browser shows the picture and plays the audio in
                // the conversation instead of downloading them.
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + name + "\"")
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
