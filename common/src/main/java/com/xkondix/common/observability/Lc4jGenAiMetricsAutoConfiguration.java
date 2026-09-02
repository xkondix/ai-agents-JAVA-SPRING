package com.xkondix.common.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import java.util.List;

/**
 * Registers the GenAI metrics/tracing listener in modules that ship LangChain4j.
 *
 * @ConditionalOnClass(ChatModelListener) keeps this inert everywhere else:
 * langchain4j-core is an OPTIONAL dependency of `common`, so Spring AI and raw
 * modules never load this configuration at all.
 *
 * ── WHY A HAND-WRITTEN LISTENER, GIVEN THAT LANGCHAIN4J NOW HAS ITS OWN ──────
 *
 * LangChain4j ships two opt-in artifacts (docs.langchain4j.dev, Spring Boot
 * Integration → Observability): langchain4j-micrometer-metrics
 * (MicrometerMetricsChatModelListener) and langchain4j-observation
 * (ObservationChatModelListener). Neither is pulled in by the starters, so
 * nothing here double-counts. This project keeps its own listener on purpose:
 *   - it is the middle rung of the "raw / listener / automatic" ladder the
 *     talk is built around, and reading it shows exactly what a listener does;
 *   - the official metrics listener records gen_ai.client.token.usage as a
 *     DistributionSummary (histogram: _count/_sum/_max), while Spring AI and
 *     raw-agent use a COUNTER (_total). Swapping it in would silently break
 *     the shared token panels — the dashboard queries *_total.
 * If you ever adopt the official artifact, add it INSTEAD of this bean, not
 * next to it, and rewrite the token panels.
 *
 * ── WHY THIS CLASS NO LONGER USES @ConditionalOnBean ──────────────────────
 *
 * It used to read:
 *
 *   @AutoConfiguration(afterName =
 *       "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
 *   ...
 *   @Bean @ConditionalOnBean(MeterRegistry.class)
 *
 * with a comment explaining the ordering pitfall: @ConditionalOnBean inside an
 * auto-configuration is evaluated in auto-configuration ORDER, at bean
 * DEFINITION time. Evaluate it before the metrics auto-configuration has
 * registered a MeterRegistry and the condition quietly fails — no listener, no
 * error.
 *
 * That guard worked on Boot 3 and stopped working on Boot 4, because the class
 * it ordered against MOVED as part of the same reorganisation that relocated
 * tracing and log export:
 *
 *   Boot 3  org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration
 *   Boot 4  org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration
 *
 * afterName takes a STRING, so a stale name is not a compile error and not a
 * startup error — the ordering hint simply matches nothing. The comment above
 * described the failure precisely and the code still walked into it.
 *
 * Symptom, for the record: LangChain4j modules produced no "chat <model>" spans
 * (Tempo showed the raw `http post` client spans instead) and no
 * gen_ai_client_token_usage{framework="langchain4j"} series, while Spring AI and
 * raw-agent kept reporting normally — so the three-framework comparison silently
 * lost one of its three legs.
 *
 * THE FIX IS NOT A BETTER CLASS NAME. Resolving the registry through
 * ObjectProvider moves the lookup from DEFINITION time to INSTANTIATION time.
 * By then every auto-configuration has contributed its bean definitions, so
 * ordering stops mattering and there is nothing left to keep in sync with
 * Spring Boot's package layout.
 *
 * Tracer is resolved the same way — with tracing disabled the listener runs in
 * metrics-only mode instead of failing to wire.
 *
 * THE FALLBACK IS LOGGED AT WARN. A SimpleMeterRegistry exports nothing; if it
 * is ever what this bean ends up with, the metrics silently vanish while every
 * log line looks healthy. The pre-demo check is: the [OBSERVABILITY] line must
 * say CompositeMeterRegistry, and there must be no WARN right after it.
 *
 * ── METERS PRE-REGISTERED AT ZERO ──────────────────────────────────────────
 *
 * Micrometer creates a meter on first use, so the token counter of a fresh
 * process is born on the first LLM call already holding e.g. 151 — the first
 * value Prometheus ever sees. rate()/increase() measure CHANGE, so that first
 * request is invisible on every rate panel and counts as $0 on the cost panel
 * (observed 2026-09-02: "Modules with LLM metrics: 2" out of 7 after a round
 * of restarts). The ApplicationRunner below registers the counters and the
 * success timer at 0 on startup, so the first push carries a zero and the
 * first increment is a real increase.
 *
 * The listener itself does not know the model until the first call, so the
 * name is read from the LangChain4j starter properties of whichever provider
 * the active profile configured. It MUST equal the model name the listener
 * later tags with (ChatResponseMetadata.modelName(), falling back to the
 * requested name) — for OpenRouter and Ollama it does; a provider that
 * answers with a different model id than it was asked for would leave the
 * pre-registered series at 0 and start a second one. Nothing breaks in that
 * case, the first request is merely invisible again.
 */
@AutoConfiguration
@ConditionalOnClass({ChatModelListener.class, MeterRegistry.class})
@EnableConfigurationProperties(GenAiContentProperties.class)
public class Lc4jGenAiMetricsAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(Lc4jGenAiMetricsAutoConfiguration.class);

    private static final String FRAMEWORK = "langchain4j";

    /** Model-name properties of the LangChain4j starters, in lookup order. */
    private static final List<String> MODEL_PROPERTIES = List.of(
            "langchain4j.open-ai.chat-model.model-name",
            "langchain4j.ollama.chat-model.model-name");

    @Bean
    ChatModelListener genAiMetricsChatModelListener(ObjectProvider<MeterRegistry> registryProvider,
                                                    ObjectProvider<Tracer> tracerProvider,
                                                    GenAiContentProperties contentProperties) {
        MeterRegistry registry = registryProvider.getIfAvailable();
        if (registry == null) {
            log.warn("[OBSERVABILITY] No MeterRegistry bean — LangChain4j gen_ai.* metrics will NOT "
                    + "be exported (in-memory SimpleMeterRegistry fallback)");
            registry = new SimpleMeterRegistry();
        }
        Tracer tracer = tracerProvider.getIfAvailable();

        // Logged at INFO on purpose: this line is the only cheap way to tell,
        // at a glance, whether LangChain4j observability is actually on. Its
        // ABSENCE is what identified the ordering bug above. The content flags
        // are included because "the prompt is missing from the span" is
        // otherwise indistinguishable from "content capture is switched off".
        log.info("[OBSERVABILITY] LangChain4j GenAI listener registered "
                        + "(metrics: gen.ai.client.* -> {}, spans: {}, "
                        + "content: prompt={} completion={} maxLen={}, framework=langchain4j)",
                registry.getClass().getSimpleName(),
                tracer != null ? "chat <model>" : "DISABLED (no Tracer bean)",
                contentProperties.includePrompt(),
                contentProperties.includeCompletion(),
                contentProperties.maxContentLength());

        return new GenAiMetricsChatModelListener(registry, tracer, contentProperties);
    }

    /**
     * Pre-registers the token counters and the success timer at 0 — see the
     * class comment. Runs after the context is up, so the registry is the real
     * (composite) one. Tags mirror GenAiMetricsChatModelListener exactly.
     */
    @Bean
    ApplicationRunner lc4jGenAiMeterWarmup(ObjectProvider<MeterRegistry> registryProvider,
                                           Environment environment) {
        return args -> {
            MeterRegistry registry = registryProvider.getIfAvailable();
            String model = MODEL_PROPERTIES.stream()
                    .map(environment::getProperty)
                    .filter(v -> v != null && !v.isBlank())
                    .findFirst()
                    .orElse(null);
            if (registry == null || model == null) {
                log.info("[OBSERVABILITY] LangChain4j meters NOT pre-registered "
                        + "(registry={}, model={}) — the first request after startup will be "
                        + "invisible to rate() panels; send a warm-up request",
                        registry != null, model);
                return;
            }
            for (String type : List.of("input", "output", "total")) {
                Counter.builder("gen.ai.client.token.usage")
                        .description("Token usage per LLM call (LangChain4j)")
                        .tag("gen_ai.operation.name", "chat")
                        .tag("gen_ai.request.model", model)
                        .tag("gen_ai.token.type", type)
                        .tag("framework", FRAMEWORK)
                        .register(registry);
            }
            Timer.builder("gen.ai.client.operation")
                    .description("Duration of a single LLM call (LangChain4j)")
                    .tag("gen_ai.operation.name", "chat")
                    .tag("gen_ai.request.model", model)
                    .tag("framework", FRAMEWORK)
                    .tag("error", "none")
                    .register(registry);
            log.info("[OBSERVABILITY] LangChain4j meters pre-registered at 0 for model={} "
                    + "(gen.ai.client.token.usage, gen.ai.client.operation)", model);
        };
    }
}
