package com.xkondix.common.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Controls whether prompt and completion CONTENT is attached to LangChain4j
 * spans, mirroring what Spring AI exposes through
 * spring.ai.chat.observations.include-prompt / include-completion.
 *
 * WHY THIS EXISTS. Spring AI can put the prompt and the completion on its
 * `chat` span; LangChain4j has no equivalent switch, because it has no
 * Micrometer integration at all. Without this, the two frameworks are not
 * comparable: the Spring AI span carries the conversation, the LangChain4j
 * span carries only token counts. Since the raw HTTP logs of the LC4j client
 * are routed to the console only (see common/logback-spring.xml), the span was
 * the last place that content could live — and it was empty.
 *
 * DEFAULTS ARE OFF, DELIBERATELY. A full prompt in telemetry is a decision,
 * not a default: it is user input, it can be large, and it leaves the process.
 * Spring AI makes the same choice.
 *
 * SIZE MATTERS MORE THAN IT LOOKS. A span is not a log store. The prompt grows
 * with every agent iteration — by the third round it contains the whole
 * history plus every tool schema — and all of it lands in ONE attribute rather
 * than one line per event. maxContentLength caps that; without it Tempo starts
 * rejecting oversized attributes, which fails the way everything else in this
 * project failed: quietly.
 *
 * Note this is NOT a cardinality concern. These are span attributes, not meter
 * tags, so a unique value per call creates no time series. The same rule as in
 * claude-mcp-server: unique-per-call data belongs on the span, never in a
 * metric.
 */
@ConfigurationProperties(prefix = "xkondix.observability.genai")
public record GenAiContentProperties(

        /** Attach the rendered request messages as gen_ai.prompt. */
        @DefaultValue("false")
        boolean includePrompt,

        /** Attach the model's answer (or its tool calls) as gen_ai.completion. */
        @DefaultValue("false")
        boolean includeCompletion,

        /** Hard cap per attribute, in characters. Longer values are truncated. */
        @DefaultValue("4000")
        int maxContentLength
) {
}
