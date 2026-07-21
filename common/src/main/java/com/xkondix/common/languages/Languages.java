package com.xkondix.common.languages;

import java.util.List;

/**
 * Target languages for the final step of the Prompt Chaining pattern.
 *
 * Single source of truth shared by BOTH patterns modules
 * (patterns-langchain4j and patterns-spring-ai) so the two mirror
 * implementations cannot drift apart. Mirrored on the client side by
 * chat-ui/src/patterns.js (LANGUAGES) — keep the two lists in sync.
 *
 * Special value MIXED: instead of one target language, the model is asked
 * to weave ALL supported languages into a single text, switching every few
 * words. It is a deliberately silly demo — and a very effective one,
 * because it proves the last link of the chain is just a prompt: no code
 * changes, no extra step, only different instructions in the same slot.
 */
public final class Languages {

    public static final String DEFAULT = "English";
    public static final String MIXED = "Mixed";

    /** Concrete languages offered in the UI (MIXED is not one of them). */
    public static final List<String> SUPPORTED = List.of(
            "English", "Polish", "Romanian", "Hindi", "Dutch", "Greek", "Turkish");

    private Languages() {
    }

    public static boolean isMixed(String language) {
        return language != null && MIXED.equalsIgnoreCase(language.trim());
    }

    /** Falls back to English for null/blank input. */
    public static String normalize(String language) {
        return language == null || language.isBlank() ? DEFAULT : language.trim();
    }

    /** "English, Polish, Romanian, Hindi, Dutch, Greek and Turkish" */
    public static String joined() {
        int last = SUPPORTED.size() - 1;
        return String.join(", ", SUPPORTED.subList(0, last)) + " and " + SUPPORTED.get(last);
    }

    /**
     * Instruction injected into the translator prompt — the ONLY thing that
     * changes between a normal translation and the mixed-language variant.
     */
    public static String instruction(String language) {
        String target = normalize(language);
        if (isMixed(target)) {
            return "Write the result as a MIX of all supported languages: " + joined()
                    + ". Switch language every few words, so that a single bullet "
                    + "contains several of them. Do not label the languages and do not "
                    + "translate the whole text into one of them — the point is the mixture. "
                    + "Keep it readable.";
        }
        return "Write the result in " + target
                + " (if it is already in " + target + ", just polish the wording).";
    }
}
