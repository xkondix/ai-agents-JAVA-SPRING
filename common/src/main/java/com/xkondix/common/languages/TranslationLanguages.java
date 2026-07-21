package com.xkondix.common.languages;

import java.util.List;

/**
 * Target languages for the final step of the Prompt Chaining pattern.
 *
 * Shared by BOTH pattern modules (patterns-langchain4j, patterns-spring-ai)
 * so the mirror implementations always offer exactly the same options —
 * the UI dropdown (chat-ui/src/patterns.js) mirrors this list.
 *
 * The special value "Mixed" is not a language: it resolves to an
 * instruction telling the model to blend ALL supported languages in one
 * text. It is a cheap but effective demo of the chaining pattern — the
 * last link is just a prompt, so changing one request parameter changes
 * the whole character of the output without touching the workflow.
 */
public final class TranslationLanguages {

    /** Everything the UI may request (plus MIXED, handled separately). */
    public static final List<String> SUPPORTED = List.of(
            "English", "Polish", "Romanian", "Hindi", "Dutch", "Greek", "Turkish");

    public static final String DEFAULT = "English";
    public static final String MIXED = "Mixed";

    private TranslationLanguages() {
    }

    /**
     * Turns the requested value into the phrase injected into the
     * translation prompt ("Translate the following into ...").
     *
     * @param requested value from the API (may be null/blank/"Mixed")
     * @return a language name, or the multilingual instruction for "Mixed"
     */
    public static String resolve(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT;
        }
        String value = requested.trim();
        return MIXED.equalsIgnoreCase(value) ? mixedInstruction() : value;
    }

    /**
     * Instruction for the "Mixed" mode — every supported language must
     * appear, switching every few words, meaning preserved.
     */
    public static String mixedInstruction() {
        return "a deliberate MIX of all these languages: " + String.join(", ", SUPPORTED)
                + ". Switch language every few words so that EVERY listed language "
                + "appears at least once, while keeping the meaning intact and "
                + "the text readable";
    }

    /** True when the caller asked for the multilingual blend. */
    public static boolean isMixed(String requested) {
        return requested != null && MIXED.equalsIgnoreCase(requested.trim());
    }
}
