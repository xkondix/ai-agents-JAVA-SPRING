package com.xkondix.patterns.lc4j.patterns;

import com.xkondix.common.languages.TranslationLanguages;
import com.xkondix.common.milan.MilanKnowledgeBase;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * PATTERN 1 — Prompt chaining (sequence).
 *
 * LangChain4j implementation: the Agentic DSL — sub-agents composed with
 * AgenticServices.sequenceBuilder(); state flows between them via outputKey
 * (each agent writes its result under a named key, the next agent reads it
 * as a @V parameter). Compare with patterns-spring-ai where the same chain
 * is three explicit ChatClient calls.
 *
 * The target language enters the shared state as a workflow argument
 * (@V("language")) and is consumed by the Translator — no glue code
 * between the links, the state carries everything.
 *
 * "Mixed" is not a language: TranslationLanguages.resolve() turns it into
 * an instruction naming every supported language, so the Translator blends
 * them all. Only the injected string changes — the workflow is untouched.
 *
 * LESSON LEARNED — the agent interfaces MUST be public:
 * dev.langchain4j.agentic.internal.AgentInvoker calls them via
 * Method.invoke() from ANOTHER package and does not call setAccessible().
 * A package-private nested interface therefore fails at runtime with
 *   IllegalAccessException: ... cannot access a member of interface ...
 *   with modifiers "public abstract"
 * even though the METHOD is public — what matters is the visibility of the
 * declaring interface. Plain AiServices (see RoutingPattern) is unaffected
 * because it goes through a proxy instead of reflective invocation.
 *
 * Trace signature in Tempo: staircase of sequential "chat" spans.
 */
@Slf4j
@Service
public class PromptChainingPattern {

    public interface ScoutAnalyst {
        @Agent("Writes a scout analysis of a squad")
        @UserMessage("Write a short scout analysis of this AC Milan squad: {{squadData}}")
        String analyze(@V("squadData") String squadData);
    }

    public interface Condenser {
        @Agent("Condenses an analysis into takeaways")
        @UserMessage("Condense this analysis into exactly 3 bullet takeaways:\n{{analysis}}")
        String condense(@V("analysis") String analysis);
    }

    public interface Translator {
        @Agent("Renders takeaways in the requested language")
        @UserMessage("Translate the following into {{language}}. "
                + "Keep the bullet format:\n{{takeaways}}")
        String translate(@V("takeaways") String takeaways, @V("language") String language);
    }

    /** Typed entry point of the whole sequence. */
    public interface ScoutReportWorkflow {
        @Agent("Produces a scout report for a squad in the requested language")
        String create(@V("squadData") String squadData, @V("language") String language);
    }

    private final ScoutReportWorkflow workflow;

    public PromptChainingPattern(ChatModel chatModel) {
        var analyst = AgenticServices.agentBuilder(ScoutAnalyst.class)
                .chatModel(chatModel)
                .outputKey("analysis")
                .build();
        var condenser = AgenticServices.agentBuilder(Condenser.class)
                .chatModel(chatModel)
                .outputKey("takeaways")
                .build();
        var translator = AgenticServices.agentBuilder(Translator.class)
                .chatModel(chatModel)
                .outputKey("translation")
                .build();

        // The DSL: the sequence IS the pattern — no hand-written handoff code
        this.workflow = AgenticServices.sequenceBuilder(ScoutReportWorkflow.class)
                .subAgents(analyst, condenser, translator)
                .outputKey("translation")
                .build();
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
        return workflow.create(squad.toString(), language);
    }
}
