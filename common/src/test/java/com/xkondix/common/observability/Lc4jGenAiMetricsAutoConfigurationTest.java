package com.xkondix.common.observability;

import dev.langchain4j.model.chat.listener.ChatModelListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The LangChain4j GenAI listener wiring — the auto-configuration whose silent
 * failure once removed one of the three frameworks from every shared panel.
 *
 * What is NOT tested here on purpose: the listener's own onRequest/onResponse
 * behaviour. Building LangChain4j's ChatModelRequestContext / ResponseContext
 * by hand couples the test to constructor shapes that change between minor
 * versions; the wiring and the meter contract are the parts worth pinning.
 */
@DisplayName("Lc4jGenAiMetricsAutoConfiguration")
class Lc4jGenAiMetricsAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(Lc4jGenAiMetricsAutoConfiguration.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    @DisplayName("registers exactly one ChatModelListener when LangChain4j is on the classpath")
    void registersListener() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ChatModelListener.class);
            assertThat(context.getBean(ChatModelListener.class))
                    .isInstanceOf(GenAiMetricsChatModelListener.class);
        });
    }

    @Test
    @DisplayName("still registers the listener with NO MeterRegistry bean (SimpleMeterRegistry fallback)")
    void listenerWithoutRegistry() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(Lc4jGenAiMetricsAutoConfiguration.class))
                .run(context -> assertThat(context).hasSingleBean(ChatModelListener.class));
    }

    @Test
    @DisplayName("pre-registers token counters and the success timer at 0 for the configured model")
    void warmupRegistersMetersAtZero() {
        runner.withPropertyValues("langchain4j.open-ai.chat-model.model-name=openai/test-model")
                .run(context -> {
                    context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
                    MeterRegistry registry = context.getBean(MeterRegistry.class);

                    for (String type : new String[] {"input", "output", "total"}) {
                        var counter = registry.find("gen.ai.client.token.usage")
                                .tag("gen_ai.request.model", "openai/test-model")
                                .tag("gen_ai.token.type", type)
                                .tag("framework", "langchain4j")
                                .counter();
                        assertThat(counter).as("token counter for type " + type).isNotNull();
                        assertThat(counter.count()).isZero();
                    }

                    var timer = registry.find("gen.ai.client.operation")
                            .tag("gen_ai.request.model", "openai/test-model")
                            .tag("error", "none")
                            .tag("framework", "langchain4j")
                            .timer();
                    assertThat(timer).isNotNull();
                    assertThat(timer.count()).isZero();
                });
    }

    @Test
    @DisplayName("falls back to the Ollama model name when no OpenAI model is configured")
    void warmupUsesOllamaModel() {
        runner.withPropertyValues("langchain4j.ollama.chat-model.model-name=llama3.1:8b")
                .run(context -> {
                    context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
                    assertThat(context.getBean(MeterRegistry.class)
                            .find("gen.ai.client.token.usage")
                            .tag("gen_ai.request.model", "llama3.1:8b")
                            .counters()).isNotEmpty();
                });
    }

    @Test
    @DisplayName("registers nothing when no model is configured — and does not fail")
    void warmupWithoutModelIsNoop() {
        runner.run(context -> {
            context.getBean(ApplicationRunner.class).run(new DefaultApplicationArguments());
            assertThat(context.getBean(MeterRegistry.class).getMeters()).isEmpty();
        });
    }
}
