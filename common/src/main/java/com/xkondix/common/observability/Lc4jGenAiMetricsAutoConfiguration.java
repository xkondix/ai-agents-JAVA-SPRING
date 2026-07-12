package com.xkondix.common.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Registers the GenAI metrics listener ONLY where it makes sense:
 *   - @ConditionalOnClass(ChatModelListener) — module has LangChain4j on the
 *     classpath (langchain4j-core is an OPTIONAL dependency of common, so
 *     Spring AI and raw modules never load this configuration),
 *   - @ConditionalOnBean(MeterRegistry) — actuator/micrometer is active.
 *
 * The LangChain4j Spring Boot starters collect all ChatModelListener beans
 * and attach them to the auto-configured ChatModel — the listener starts
 * working without touching any model definition.
 *
 * Spring AI modules do NOT need an equivalent: ChatClient emits
 * gen_ai.client.* metrics out of the box via its own observation
 * conventions. This class exists precisely to level the playing field.
 */
@AutoConfiguration
@ConditionalOnClass({ChatModelListener.class, MeterRegistry.class})
public class Lc4jGenAiMetricsAutoConfiguration {

    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    ChatModelListener genAiMetricsChatModelListener(MeterRegistry registry) {
        return new GenAiMetricsChatModelListener(registry);
    }
}
