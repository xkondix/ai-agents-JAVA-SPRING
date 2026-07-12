package com.xkondix.common.observability;

import io.micrometer.observation.ObservationPredicate;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Shared observability tuning for ALL modules (loaded via
 * META-INF/spring/...AutoConfiguration.imports — no component scan needed,
 * which is why this works even though each app has a different base package).
 *
 * Problem it solves: the chat-ui polls /actuator/health of every agent every
 * few seconds. With sampling.probability=1.0 each poll becomes a full trace,
 * flooding Grafana Tempo and burying the interesting chat traces. It also
 * inflates http.server.requests metrics in Prometheus.
 *
 * The ObservationPredicate below rejects observations for actuator requests
 * BEFORE they are created, so neither spans nor http.server.requests metrics
 * are produced for them.
 *
 * What it does NOT affect:
 *   - custom Counters/Timers registered directly on MeterRegistry
 *   - manually created spans (Tracer API)
 *   - gen_ai.* observations from Spring AI (different context type)
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnClass({ObservationPredicate.class, ServerRequestObservationContext.class})
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    /**
     * Drop HTTP server observations for configured path prefixes
     * (default: /actuator). Returning false here means: no span,
     * no http.server.requests metric for this request.
     */
    @Bean
    ObservationPredicate excludedPathsObservationPredicate(ObservabilityProperties props) {
        return (name, context) -> {
            if (context instanceof ServerRequestObservationContext serverContext) {
                String uri = serverContext.getCarrier().getRequestURI();
                return props.excludedPathPrefixes().stream().noneMatch(uri::startsWith);
            }
            return true;
        };
    }
}
