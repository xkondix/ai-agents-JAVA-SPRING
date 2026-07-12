package com.xkondix.rawagent.agent;

import com.xkondix.rawagent.model.ChatResponse;
import com.xkondix.rawagent.model.Message;
import com.xkondix.rawagent.model.ToolCall;
import com.xkondix.rawagent.tools.DemoTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the agent loop — no LLM, no HTTP, no Spring context.
 *
 * The point of these tests (and a good talking point for the presentation):
 * because the loop is plain Java, it is fully testable with a mocked
 * LlmClient. Frameworks hide the loop, which also makes it harder to
 * test at this level of precision.
 */
@ExtendWith(MockitoExtension.class)
class RawAgentLoopTest {

    @Mock
    private LlmClient llmClient;

    @Mock
    private DemoTools tools;

    @Captor
    private ArgumentCaptor<List<Message>> historyCaptor;

    // ── Helpers ───────────────────────────────────────────────────────────

    private RawAgentLoop loop() {
        return new RawAgentLoop(llmClient, tools);
    }

    private static ChatResponse textResponse(String content) {
        return new ChatResponse("id-1",
                List.of(new ChatResponse.Choice(0,
                        Message.assistant(content, null), "stop")),
                new ChatResponse.Usage(10, 20, 30));
    }

    private static ChatResponse toolCallResponse(String callId,
                                                 String toolName,
                                                 String arguments) {
        ToolCall call = new ToolCall(callId, "function",
                new ToolCall.Function(toolName, arguments));
        return new ChatResponse("id-2",
                List.of(new ChatResponse.Choice(0,
                        Message.assistant(null, List.of(call)), "tool_calls")),
                new ChatResponse.Usage(15, 5, 20));
    }

    // ── Happy paths ───────────────────────────────────────────────────────

    @Test
    void returnsAnswerDirectly_whenModelRequestsNoTools() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(textResponse("Hello, Konrad!"));

        String result = loop().chat("Hi");

        assertThat(result).isEqualTo("Hello, Konrad!");
        verify(llmClient, times(1)).chat(anyList(), anyList());
    }

    @Test
    void executesTool_andFeedsResultBackIntoHistory() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(toolCallResponse("call-42", "calculateSquare", "{\"number\":7}"))
                .thenReturn(textResponse("The square is 49."));
        when(tools.execute("calculateSquare", "{\"number\":7}"))
                .thenReturn("49");

        String result = loop().chat("What is 7 squared?");

        assertThat(result).isEqualTo("The square is 49.");
        verify(tools).execute("calculateSquare", "{\"number\":7}");

        // Second LLM call must contain the tool result in history,
        // linked to the original tool_call_id.
        verify(llmClient, times(2)).chat(historyCaptor.capture(), anyList());
        List<Message> secondCallHistory = historyCaptor.getAllValues().get(1);
        assertThat(secondCallHistory)
                .anySatisfy(msg -> {
                    assertThat(msg.role()).isEqualTo("tool");
                    assertThat(msg.toolCallId()).isEqualTo("call-42");
                    assertThat(msg.content()).isEqualTo("49");
                });
    }

    @Test
    void historyStartsWithSystemAndUserMessages() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(textResponse("ok"));

        loop().chat("ping");

        verify(llmClient).chat(historyCaptor.capture(), anyList());
        List<Message> history = historyCaptor.getValue();
        assertThat(history.get(0).role()).isEqualTo("system");
        assertThat(history.get(1).role()).isEqualTo("user");
        assertThat(history.get(1).content()).isEqualTo("ping");
    }

    // ── Safety limits ─────────────────────────────────────────────────────

    @Test
    void stopsAfterMaxIterations_whenModelKeepsRequestingTools() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(toolCallResponse("call-1", "getCurrentTime", "{}"));
        when(tools.execute(anyString(), anyString())).thenReturn("2026-07-07 12:00:00");

        String result = loop().chat("Loop forever please");

        assertThat(result).contains("Max iterations");
        verify(llmClient, times(10)).chat(anyList(), anyList());
    }

    // ── Graceful degradation ──────────────────────────────────────────────

    @Test
    void returnsFriendlyMessage_whenOllamaIsDown() {
        when(llmClient.chat(anyList(), anyList()))
                .thenThrow(new RuntimeException(
                        "LLM call failed: Connection refused"));

        String result = loop().chat("Hi");

        assertThat(result).contains("Ollama").contains("11434");
    }

    @Test
    void returnsReadableError_whenApiReturnsErrorStatus() {
        when(llmClient.chat(anyList(), anyList()))
                .thenThrow(new RuntimeException("LLM API error 500: boom"));

        String result = loop().chat("Hi");

        assertThat(result).startsWith("LLM returned an error");
    }

    @Test
    void returnsError_whenResponseHasNoChoices() {
        when(llmClient.chat(anyList(), anyList()))
                .thenReturn(new ChatResponse("id-3", List.of(), null));

        String result = loop().chat("Hi");

        assertThat(result).contains("empty response");
    }

    @Test
    void returnsEmptyString_whenFinalMessageHasNullContent() {
        when(llmClient.chat(any(), any()))
                .thenReturn(new ChatResponse("id-4",
                        List.of(new ChatResponse.Choice(0,
                                Message.assistant(null, null), "stop")),
                        null));

        String result = loop().chat("Hi");

        assertThat(result).isEmpty();
    }
}
