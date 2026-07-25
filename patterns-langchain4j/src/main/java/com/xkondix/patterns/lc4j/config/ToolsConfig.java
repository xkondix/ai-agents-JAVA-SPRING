package com.xkondix.patterns.lc4j.config;

import com.xkondix.common.observability.TracingToolProvider;
import com.xkondix.patterns.lc4j.tools.MilanTools;
import dev.langchain4j.service.tool.ToolProvider;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the AC Milan tools as an INSTRUMENTED ToolProvider.
 *
 * Why not AiServices.tools(milanTools)? Tools passed that way are executed
 * inside AiServices without going through a ToolProvider, so nothing can
 * decorate them — LangChain4j emits no tool spans of its own and the traces
 * showed only "chat" spans, making the LC4j implementation look simpler than
 * it is next to Spring AI (which instruments tools automatically).
 *
 * Building the provider from the same annotated object and wrapping it in
 * TracingToolProvider gives us "tool_call <name>" spans with no changes to
 * the tool code itself.
 */
@Configuration
public class ToolsConfig {

    @Bean
    ToolProvider milanToolProvider(MilanTools milanTools,
                                   ObjectProvider<Tracer> tracerProvider) {
        return TracingToolProvider.fromAnnotatedObject(
                milanTools, tracerProvider.getIfAvailable());
    }
}
