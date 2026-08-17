package com.xkondix.claude.mcp.server.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Hands the configured OpenTelemetry SDK to the Logback appender declared in
 * logback-spring.xml, so log records are exported over OTLP to Loki.
 *
 * WHY THIS CLASS HAS TO EXIST. Spring Boot 4 auto-configures the OTel *logging
 * SDK* — set management.opentelemetry.logging.export.otlp.endpoint and a
 * BatchLogRecordProcessor with an OTLP exporter is created for you. What Boot
 * does NOT do is connect Logback to it. The bridge is
 * opentelemetry-logback-appender-1.0, and it is inert until someone calls
 * OpenTelemetryAppender.install(openTelemetry). This is the pattern from the
 * Spring Boot reference documentation, not an invention.
 *
 * That split is easy to get wrong in exactly the quiet way this project keeps
 * collecting: the endpoint property binds, the SDK starts, no error appears
 * anywhere — and not a single log line reaches Loki, because nothing ever fed
 * the appender.
 *
 * TIMING: log records emitted before this bean initializes are buffered by the
 * appender (a bounded queue) and flushed on install. Very early startup lines
 * can still be dropped, which is acceptable — they are also the least
 * interesting ones, and they are in the file log regardless.
 *
 * VERSION PAIRING: the appender lives in the io.opentelemetry.instrumentation
 * group, which Spring Boot does NOT version-manage (it manages
 * io.opentelemetry:opentelemetry-bom only). The pom pins 2.21.0-alpha because
 * that instrumentation release targets OTel SDK 1.55.0 — the exact version
 * Boot 4.0.0 ships. Bumping Boot means re-checking that pairing.
 */
@Component
class OpenTelemetryAppenderInitializer implements InitializingBean {

    private final OpenTelemetry openTelemetry;

    OpenTelemetryAppenderInitializer(OpenTelemetry openTelemetry) {
        this.openTelemetry = openTelemetry;
    }

    @Override
    public void afterPropertiesSet() {
        OpenTelemetryAppender.install(this.openTelemetry);
    }
}
