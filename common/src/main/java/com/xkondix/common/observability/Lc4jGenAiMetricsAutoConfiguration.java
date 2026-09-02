package com.xkondix.common.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Registers the GenAI metrics/tracing listener in modules that ship LangChain4j.
 *
 * @ConditionalOnClass(ChatModelListener) keeps this inert everywhere else:
 * langchain4j-core is an OPTIONAL dependency of `common`, so Spring AI and raw
 * modules never load this configuration at all.
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
 */
@AutoConfiguration
@ConditionalOnClass({ChatModelListener.class, MeterRegistry.class})
@EnableConfigurationProperties(GenAiContentProperties.class)
public class Lc4jGenAiMetricsAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(Lc4jGenAiMetricsAutoConfiguration.class);

    @Bean
    ChatModelListener genAiMetricsChatModelListener(ObjectProvider<MeterRegistry> registryProvider,
                                                    ObjectProvider<Tracer> tracerProvider,
                                                    GenAiContentProperties contentProperties) {
        MeterRegistry registry = registryProvider.getIfAvailable(SimpleMeterRegistry::new);
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
}
