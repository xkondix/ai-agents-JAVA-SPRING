package com.xkondix.multimodal.media;

/**
 * One thing the agent produced, with enough context to make sense of it later.
 *
 * WHY THE PROMPT IS STORED ALONGSIDE THE URL. A gallery of twelve generated
 * pictures is unusable without knowing what each one was asked for — and the
 * conversation that produced them scrolls away, or gets wiped by "Reset
 * memory". The artifact outlives the thread on purpose (files are never
 * deleted), so it has to carry its own explanation.
 *
 * `kind` drives both the gallery tab and the renderer: an mp3 from speak_text
 * and an mp3 from Lyria are the same file format and completely different
 * things to a listener. Extension-sniffing would merge them; this does not.
 *
 * TEXT IS AN ARTIFACT TOO. What the model chose to have read out loud is
 * worth keeping next to the audio — it is the only way to tell a bad voice
 * from a bad script when a demo goes sideways.
 */
public record Artifact(
        String url,
        Kind kind,
        String prompt,
        long createdAt) {

    public enum Kind {
        /** generate_image */
        IMAGE,
        /** speak_text — narration produced by a TTS model */
        SPEECH,
        /** music generation (Lyria) — mp3 like SPEECH, but not narration */
        MUSIC,
        /** video generation */
        VIDEO,
        /** the words handed to speak_text, kept so the script is reviewable */
        TEXT,
        /** an image the user attached */
        UPLOAD;

        public String lower() {
            return name().toLowerCase();
        }
    }

    public static Artifact of(String url, Kind kind, String prompt) {
        return new Artifact(url, kind, prompt, System.currentTimeMillis());
    }
}
