package com.xkondix.patterns.lc4j.patterns;

import com.xkondix.common.milan.MilanKnowledgeBase;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PATTERN 3 — Parallelization (sectioning).
 *
 * Independent sub-tasks run concurrently; CODE aggregates.
 *
 * LangChain4j implementation: an AiServices scorer returning a typed POJO
 * (LC4j parses the JSON for us) fanned out with CompletableFuture on
 * virtual threads. The Agentic DSL has a parallel flavour too, but the
 * executor version keeps the mechanics visible — and is identical to the
 * Spring AI module, which makes the frameworks directly comparable here.
 *
 * Trace signature: OVERLAPPING "chat" spans with a common start.
 */
@Slf4j
@Service
public class ParallelizationPattern {

    public record CandidateScore(String player, double score, String rationale) {}

    interface CandidateScorer {
        @UserMessage("Score this transfer candidate for AC Milan from 0.0 to 1.0 "
                + "considering probability and squad needs. Candidate: {{it}}")
        CandidateScore score(String rumor);
    }

    private final CandidateScorer scorer;
    private final ExecutorService executor =
            Executors.newVirtualThreadPerTaskExecutor();

    public ParallelizationPattern(ChatModel chatModel) {
        this.scorer = AiServices.builder(CandidateScorer.class)
                .chatModel(chatModel)
                .build();
    }

    public String run() {
        var candidates = MilanKnowledgeBase.secretRumors();
        log.info("[PARALLEL] fan-out over {} candidates", candidates.size());

        List<CompletableFuture<CandidateScore>> futures = candidates.stream()
                .map(rumor -> CompletableFuture.supplyAsync(
                        () -> scorer.score(rumor.toString()), executor))
                .toList();

        List<CandidateScore> scores = futures.stream()
                .map(CompletableFuture::join)
                .toList();
        scores.forEach(s -> log.info("[PARALLEL] {} -> {}", s.player(), s.score()));

        CandidateScore best = scores.stream()
                .max(Comparator.comparingDouble(CandidateScore::score))
                .orElseThrow();

        return "Best candidate: " + best.player()
                + " (score " + best.score() + ") — " + best.rationale()
                + "\n\nAll scores: " + scores;
    }
}
