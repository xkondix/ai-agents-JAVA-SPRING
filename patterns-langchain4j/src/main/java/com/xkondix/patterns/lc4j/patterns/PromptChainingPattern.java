package com.xkondix.patterns.lc4j.patterns;

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
 * The target language chosen in the UI enters the shared state as a
 * workflow argument (@V("language")) and is consumed by the Translator —
 * no code between the links, the state carries everything.
 *
 * Trace signature in Tempo: staircase of sequential "chat" spans.
 *
 * Demo: season year -> scout analysis (EN) -> 3 key takeaways ->
 * translation into the requested language (default: English).
 */
@Slf4j
@Service
public class PromptChainingPattern {

    interface ScoutAnalyst {
        @Agent("Writes a scout analysis of a squad")
        @UserMessage("Write a short scout analysis of this AC Milan squad: {{squadData}}")
        String analyze(@V("squadData") String squadData);
    }

    interface Condenser {
        @Agent("Condenses an analysis into takeaways")
        @UserMessage("Condense this analysis into exactly 3 bullet takeaways:\n{{analysis}}")
        String condense(@V("analysis") String analysis);
    }

    interface Translator {
        @Agent("Translates takeaways into the requested language")
        @UserMessage("Translate the following into {{language}} (if it is already "
                + "in {{language}}, just polish the wording). Keep the bullet format:\n{{takeaways}}")
        String translate(@V("takeaways") String takeaways, @V("language") String language);
    }

    /** Typed entry point of the whole sequence. */
    interface ScoutReportWorkflow {
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
        String language = targetLanguage == null || targetLanguage.isBlank()
                ? "English" : targetLanguage.trim();
        log.info("[CHAIN] season={} language={}", seasonYear, language);
        var squad = MilanKnowledgeBase.squad(seasonYear);
        if (squad.isEmpty()) {
            return "No data for season " + seasonYear
                    + ". Available: " + MilanKnowledgeBase.availableSeasons();
        }
        return workflow.create(squad.toString(), language);
    }
}
