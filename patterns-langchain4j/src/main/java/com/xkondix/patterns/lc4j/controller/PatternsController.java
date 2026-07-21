package com.xkondix.patterns.lc4j.controller;

import com.xkondix.patterns.lc4j.patterns.EvaluatorOptimizerPattern;
import com.xkondix.patterns.lc4j.patterns.OrchestratorWorkersPattern;
import com.xkondix.patterns.lc4j.patterns.ParallelizationPattern;
import com.xkondix.patterns.lc4j.patterns.PromptChainingPattern;
import com.xkondix.patterns.lc4j.patterns.RoutingPattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * One endpoint per workflow pattern. Mirrors patterns-spring-ai (port 8088)
 * 1:1 — same paths, same payloads, different framework underneath.
 * Compare the two implementations side by side, then compare the traces.
 *
 * @RequestParam carries an EXPLICIT name: our custom parent pom compiles
 * with -parameters now, but explicit names keep the endpoints working
 * regardless of build configuration (IDE builds included).
 */
@RestController
@RequestMapping("/api/v1/patterns")
@RequiredArgsConstructor
public class PatternsController {

    private final PromptChainingPattern chaining;
    private final RoutingPattern routing;
    private final ParallelizationPattern parallelization;
    private final EvaluatorOptimizerPattern evaluator;
    private final OrchestratorWorkersPattern orchestrator;

    /** e.g. GET /api/v1/patterns/chain?season=2007&language=Polish */
    @GetMapping("/chain")
    public String chain(
            @RequestParam(name = "season", defaultValue = "2007") int season,
            @RequestParam(name = "language", defaultValue = "English") String language) {
        return chaining.run(season, language);
    }

    /** e.g. POST /api/v1/patterns/routing  body: "kto gral w pomocy w 2007?" */
    @PostMapping("/routing")
    public String routing(@RequestBody String question) {
        return routing.run(question);
    }

    /** GET /api/v1/patterns/parallel — scores all rumor candidates concurrently */
    @GetMapping("/parallel")
    public String parallel() {
        return parallelization.run();
    }

    /** e.g. GET /api/v1/patterns/evaluator?season=2007 */
    @GetMapping("/evaluator")
    public String evaluator(@RequestParam(name = "season", defaultValue = "2007") int season) {
        return evaluator.run(season);
    }

    /** e.g. POST /api/v1/patterns/orchestrator  body: "compare the 2007 and 2024 squads" */
    @PostMapping("/orchestrator")
    public String orchestrator(@RequestBody String task) {
        return orchestrator.run(task);
    }
}
