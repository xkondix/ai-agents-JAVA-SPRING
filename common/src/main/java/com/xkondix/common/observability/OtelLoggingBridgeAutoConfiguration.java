package com.xkondix.common.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Arms the Logback -> OpenTelemetry bridge for every module that depends on
 * `common`.
 *
 * WHY THIS CLASS HAS TO EXIST. Spring Boot 4 auto-configures the OpenTelemetry
 * logging SDK and the OTLP log exporter, and common/logback-spring.xml declares
 * the appender — but nothing connects the two. OpenTelemetryAppender is created
 * by Logback, outside the Spring context, so it has no way to reach the
 * OpenTelemetry bean on its own. Until install() hands it that bean, the
 * appender accepts log records and drops them.
 *
 * The failure mode is completely silent: the endpoint property binds, the SDK
 * starts, the appender is attached to root, no error is logged anywhere, and
 * Loki simply stays empty. "Logs for this span" in Tempo then opens a correctly
 * built query that returns nothing, which reads like a Grafana problem and is
 * not one.
 *
 * ORDERING. install() runs in afterPropertiesSet(), i.e. once the OpenTelemetry
 * bean exists. Records logged before that point are buffered by the appender
 * (default capacity 1000) and flushed on install, so early startup lines are
 * not lost — but a long, log-heavy startup can overflow that buffer, and the
 * overflow is not reported either.
 *
 * CONDITIONS. @ConditionalOnClass keeps this inert where the bridge is not on
 * the classpath, so `common` stays usable as a plain library. The property
 * switch exists for tests and for running without an LGTM stack:
 *   xkondix.observability.otel-logging.enabled=false
 */
@AutoConfiguration
@ConditionalOnClass({ OpenTelemetry.class, OpenTelemetryAppender.class })
@ConditionalOnProperty(name = "xkondix.observability.otel-logging.enabled",
        havingValue = "true", matchIfMissing = true)
public class OtelLoggingBridgeAutoConfiguration {

    @Bean
    OtelAppenderInstaller otelAppenderInstaller(OpenTelemetry openTelemetry) {
        return new OtelAppenderInstaller(openTelemetry);
    }

    /**
     * InitializingBean rather than @PostConstruct so the ordering is explicit
     * and does not depend on annotation processing being active.
     */
    static class OtelAppenderInstaller implements InitializingBean {

        private static final Logger log = LoggerFactory.getLogger(OtelAppenderInstaller.class);

        private final OpenTelemetry openTelemetry;

        OtelAppenderInstaller(OpenTelemetry openTelemetry) {
            this.openTelemetry = openTelemetry;
        }

        @Override
        public void afterPropertiesSet() {
            OpenTelemetryAppender.install(this.openTelemetry);
            log.info("OpenTelemetry Logback appender installed — logs now export over OTLP");
        }
    }
}
