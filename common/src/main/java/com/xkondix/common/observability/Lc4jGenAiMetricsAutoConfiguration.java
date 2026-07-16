package com.xkondix.common.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Registers the GenAI metrics/tracing listener ONLY where it makes sense:
 *   - @ConditionalOnClass(ChatModelListener) — module has LangChain4j on the
 *     classpath (langchain4j-core is an OPTIONAL dependency of common, so
 *     Spring AI and raw modules never load this configuration),
 *   - @ConditionalOnBean(MeterRegistry) — actuator/micrometer is active.
 *
 * ORDERING PITFALL (this bit us): @ConditionalOnBean inside an
 * auto-configuration is evaluated in auto-configuration order. Without
 * ordering, this class could be evaluated BEFORE actuator registers the
 * MeterRegistry — the condition silently fails and the listener never
 * exists, with zero errors in the log.
 *
 * Note `afterName` (string) instead of `after` (class): the actuator
 * autoconfigure classes are not on common's compile classpath (they come
 * from each consumer's spring-boot-starter-actuator), and ordering by name
 * needs no compile-time dependency.
 *
 * Tracer is injected as ObjectProvider — with tracing disabled the
 * listener runs in metrics-only mode instead of failing to wire.
 */
@AutoConfiguration(afterName =
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration")
@ConditionalOnClass({ChatModelListener.class, MeterRegistry.class})
public class Lc4jGenAiMetricsAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(Lc4jGenAiMetricsAutoConfiguration.class);

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    ChatModelListener genAiMetricsChatModelListener(MeterRegistry registry,
                                                    ObjectProvider<Tracer> tracerProvider) {
        Tracer tracer = tracerProvider.getIfAvailable();
        log.info("[OBSERVABILITY] LangChain4j GenAI listener registered "
                + "(metrics: gen.ai.client.*, spans: {}, framework=langchain4j)",
                tracer != null ? "chat <model>" : "DISABLED (no Tracer bean)");
        return new GenAiMetricsChatModelListener(registry, tracer);
    }
}
