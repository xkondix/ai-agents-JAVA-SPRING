package com.xkondix.multimodal.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Speech-to-text — the one modality that is NOT a tool.
 *
 * Transcription runs BEFORE the model is asked anything: a voice note is the
 * question, not something the agent decides to fetch. Exposing it as a @Tool
 * would be backwards — the model would have to know a recording exists in
 * order to ask for its contents, and it only knows that if we already told
 * it, which requires the transcript.
 *
 * Vision input is the same shape of problem solved differently: an uploaded
 * photo travels WITH the chat request as Media (see MultimodalAgentService),
 * because a picture is context for the question rather than an action.
 *
 * So the module ends up with a clean split worth pointing at:
 *   INPUT modalities  (image, audio) → attached to, or resolved before, the prompt
 *   OUTPUT modalities (image, speech) → tools the model chooses to call
 */
@Slf4j
@Service
public class TranscriptionService {

    private final TranscriptionModel transcriptionModel;
    private final MeterRegistry registry;

    public TranscriptionService(TranscriptionModel transcriptionModel, MeterRegistry registry) {
        this.transcriptionModel = transcriptionModel;
        this.registry = registry;
    }

    /**
     * @param audio raw bytes of a recording (webm/ogg/mp3/wav — whatever the
     *              browser's MediaRecorder produced)
     * @param filename original name; the extension matters, the provider uses
     *                 it to pick a decoder
     */
    public String transcribe(byte[] audio, String filename) {
        log.info("[MM] transcribe: {} ({} bytes)", filename, audio.length);
        Timer.Sample sample = Timer.start(registry);
        try {
            // A named resource, not a bare byte[]: the multipart filename is
            // how the API infers the audio format. An anonymous resource is
            // rejected with an unhelpful "unsupported file format".
            Resource resource = new ByteArrayResource(audio) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            String text = transcriptionModel.call(new AudioTranscriptionPrompt(resource))
                    .getResult()
                    .getOutput();

            log.info("[MM] transcribed {} chars", text == null ? 0 : text.length());
            record(audio.length, sample);
            return text == null ? "" : text;

        } catch (RuntimeException e) {
            log.error("[MM] transcription failed: {}", e.getMessage());
            record(0, sample);
            throw new IllegalStateException("Could not transcribe audio: " + e.getMessage(), e);
        }
    }

    private void record(int bytes, Timer.Sample sample) {
        sample.stop(Timer.builder("mm.generation.duration")
                .description("Duration of a non-chat generation call")
                .tag("modality", "transcription")
                .tag("framework", "spring-ai")
                .register(registry));

        registry.counter("mm.generation.calls",
                "modality", "transcription", "framework", "spring-ai").increment();

        if (bytes > 0) {
            registry.summary("mm.generation.size",
                    "modality", "transcription", "framework", "spring-ai").record(bytes);
        }
    }
}
