package com.xkondix.lc4j.agent.service;

import com.xkondix.common.observability.TracingToolProvider;
import com.xkondix.lc4j.agent.tools.DemoTools;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.tool.ToolProvider;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * AiServices — the loop is hidden by the framework.
 *
 * Observability note: tools are supplied through an INSTRUMENTED
 * ToolProvider instead of AiServices.tools(demoTools). Tools passed the
 * .tools(...) way are executed inside AiServices without going through a
 * provider, so nothing can decorate them — LangChain4j emits no tool spans
 * of its own and Tempo showed only "chat" spans, hiding half of what the
 * agent actually did.
 *
 * TracingToolProvider (module `common`) builds the same tool set from the
 * same annotated object and wraps every executor in a "tool_call <name>"
 * span, so this module's traces now match Spring AI and raw-agent:
 *   http post → chat → tool_call → chat
 */
@Slf4j
@Service
public class AiServicesAgentService {

    public interface Assistant {
        @SystemMessage("You are a helpful assistant. Use tools when appropriate.")
        String chat(@MemoryId String userId, @UserMessage String message);
    }

    private final Assistant assistant;

    public AiServicesAgentService(ChatModel model,
                                  DemoTools demoTools,
                                  ObjectProvider<Tracer> tracerProvider) {
        ToolProvider toolProvider = TracingToolProvider.fromAnnotatedObject(
                demoTools, tracerProvider.getIfAvailable());

        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(model)
                .toolProvider(toolProvider)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.withMaxMessages(20))
                .build();
    }

    public String chat(String userId, String message) {
        log.info("AiServices chat: userId={}", userId);
        return assistant.chat(userId, message);
    }
}
