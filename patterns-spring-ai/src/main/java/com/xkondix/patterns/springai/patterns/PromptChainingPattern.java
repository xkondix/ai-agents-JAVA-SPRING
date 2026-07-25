package com.xkondix.patterns.springai.patterns;

import com.xkondix.common.languages.TranslationLanguages;
import com.xkondix.common.milan.MilanKnowledgeBase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * PATTERN 1 — Prompt chaining (sequence).
 *
 * Output of step N is the input of step N+1; the decomposition is designed
 * by the developer, not the model. Each link can have its own prompt,
 * model and validation gate.
 *
 * Spring AI implementation: plain Java — three ChatClient calls in a row.
 * (LangChain4j equivalent: AgenticServices.sequenceBuilder().)
 *
 * Trace signature in Tempo: staircase — sequential "chat" spans,
 * each starting when the previous one ends.
 *
 * Demo: season year -> scout analysis (EN) -> 3 key takeaways ->
 * translation into the requested language. The language may also be
 * "Mixed", which asks the model to blend ALL supported languages
 * (see TranslationLanguages in common) — the workflow stays identical,
 * only the last prompt changes. That is the whole point of the pattern:
 * links are independent, so you can swap one without touching the others.
 */
@Slf4j
@Service
public class PromptChainingPattern {

    private final ChatClient plainAgent;

    public PromptChainingPattern(@Qualifier("plainAgent") ChatClient plainAgent) {
        this.plainAgent = plainAgent;
    }

    public String run(int seasonYear, String targetLanguage) {
        // "Mixed" resolves to an instruction listing every supported language
        String language = TranslationLanguages.resolve(targetLanguage);
        log.info("[CHAIN] season={} language={}", seasonYear,
                TranslationLanguages.isMixed(targetLanguage) ? "MIXED (all)" : language);

        var squad = MilanKnowledgeBase.squad(seasonYear);
        if (squad.isEmpty()) {
            return "No data for season " + seasonYear
                    + ". Available: " + MilanKnowledgeBase.availableSeasons();
        }

        // Step 1 — scout analysis from raw data (data injected by CODE,
        // not fetched by the model: the chain is choreographed by us)
        String analysis = plainAgent.prompt()
                .user("Write a short scout analysis of this AC Milan squad ("
                        + seasonYear + "): " + squad)
                .call()
                .content();
        log.info("[CHAIN] step 1 done ({} chars)", analysis.length());

        // Step 2 — condense; a validation gate could sit between links
        String takeaways = plainAgent.prompt()
                .user("Condense this analysis into exactly 3 bullet takeaways:\n" + analysis)
                .call()
                .content();
        log.info("[CHAIN] step 2 done");

        // Step 3 — render in the requested language (or the mixed blend).
        // English also goes through the model (as a polish/format step) so
        // the chain always has three links and traces stay comparable.
        String translated = plainAgent.prompt()
                .user("Translate the following into " + language
                        + ". Keep the bullet format:\n" + takeaways)
                .call()
                .content();
        log.info("[CHAIN] step 3 done");

        return translated;
    }
}
