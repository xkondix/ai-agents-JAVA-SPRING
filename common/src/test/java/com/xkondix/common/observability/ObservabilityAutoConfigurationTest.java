package com.xkondix.common.observability;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two shared observability rules, tested without booting an application.
 *
 * Both have failed silently before: a wrong path prefix floods Tempo with
 * health-check traces, and a timer name the histogram filter does not match
 * leaves p95 panels empty while the avg panel next to them works. Neither
 * shows up in a log — these tests are the only early warning.
 */
@DisplayName("ObservabilityAutoConfiguration")
class ObservabilityAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    @Test
    @DisplayName("registers the predicate and the histogram filter in a servlet web app")
    void registersBeansInServletApp() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ObservationPredicate.class);
            assertThat(context).hasSingleBean(MeterFilter.class);
        });
    }

    @Test
    @DisplayName("stays inert outside a servlet web application")
    void inertOutsideWebApp() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ObservationPredicate.class));
    }

    @Test
    @DisplayName("drops observations for /actuator/** and keeps everything else")
    void predicateFiltersActuator() {
        runner.run(context -> {
            ObservationPredicate predicate = context.getBean(ObservationPredicate.class);

            assertThat(predicate.test("http.server.requests", serverContext("/actuator/health")))
                    .as("health polling must produce no span and no metric")
                    .isFalse();
            assertThat(predicate.test("http.server.requests", serverContext("/api/v1/agent/chat")))
                    .isTrue();
        });
    }

    @Test
    @DisplayName("honours a custom excluded prefix list")
    void predicateHonoursCustomPrefixes() {
        runner.withPropertyValues("xkondix.observability.excluded-path-prefixes=/internal")
                .run(context -> {
                    ObservationPredicate predicate = context.getBean(ObservationPredicate.class);
                    assertThat(predicate.test("x", serverContext("/internal/ping"))).isFalse();
                    assertThat(predicate.test("x", serverContext("/actuator/health"))).isTrue();
                });
    }

    @Test
    @DisplayName("gives histogram buckets to BOTH spellings of the chat timer and to the tool timers")
    void histogramBucketsForLatencyTimers() {
        runner.run(context -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(context.getBean(MeterFilter.class));

            // raw-agent / LC4j listener spelling
            assertThat(bucketCount(registry, "gen.ai.client.operation")).isEqualTo(8);
            // Spring AI spelling — the one the first version of the filter missed
            assertThat(bucketCount(registry, "gen_ai.client.operation")).isEqualTo(8);
            assertThat(bucketCount(registry, "spring.ai.tool")).isEqualTo(8);
            assertThat(bucketCount(registry, "mcp.tool.duration")).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("leaves unrelated timers without buckets")
    void noBucketsForOtherTimers() {
        runner.run(context -> {
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(context.getBean(MeterFilter.class));

            assertThat(bucketCount(registry, "http.server.requests")).isZero();
        });
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ServerRequestObservationContext serverContext(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }

    private static int bucketCount(SimpleMeterRegistry registry, String timerName) {
        Timer timer = Timer.builder(timerName).register(registry);
        return timer.takeSnapshot().histogramCounts().length;
    }
}
