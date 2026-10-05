package com.xkondix.springai.agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * A hand-written Advisor, kept as the one example of the pattern in this repo.
 *
 * ── WHAT AN ADVISOR IS ─────────────────────────────────────────────────────
 *
 * A filter in a chain, the same shape as a servlet filter. Advisors are
 * ordered by getOrder(); a request passes through before() in ascending order,
 * goes to the model, and the response comes back through after() in DESCENDING
 * order.
 *
 * The part that makes it more than a listener: before() RETURNS the request
 * and after() RETURNS the response. An advisor can replace what travels on.
 * MessageChatMemoryAdvisor does exactly that — it appends the stored
 * conversation to the request before the model ever sees it. LangChain4j's
 * ChatModelListener, by contrast, observes and cannot change anything.
 *
 * ── THE LOOP IS AN ADVISOR TOO ─────────────────────────────────────────────
 *
 * In Spring AI 2.0 tool execution was lifted out of the ChatModel and into
 * this same chain: ChatClient registers a ToolCallingAdvisor automatically
 * whenever .tools(...) is present. It does not merely observe the response —
 * it sees tool_calls, executes them, appends the results and RE-ENTERS the
 * downstream chain, looping until no tool calls remain.
 *
 * So the agent loop is literally a link in the list this class belongs to.
 * That is the structural difference from LangChain4j: AiServices hides the
 * loop behind a proxy you cannot change, while Spring AI hides it in a
 * structure you can add to.
 *
 * ── ORDER 0 PUTS THIS ADVISOR *INSIDE* THE LOOP ────────────────────────────
 *
 * This is worth reading twice, because the number is misleading. The relevant
 * default orders are:
 *
 *     MessageChatMemoryAdvisor   HIGHEST_PRECEDENCE + 200
 *     ToolCallingAdvisor         HIGHEST_PRECEDENCE + 300
 *     this advisor               0
 *
 * HIGHEST_PRECEDENCE is Integer.MIN_VALUE, so a plain 0 is enormously LARGER
 * than MIN_VALUE + 300. Larger order means later in the chain, and anything
 * later than ToolCallingAdvisor is re-entered on every lap of the loop.
 *
 * Consequence: this advisor logs ONCE PER ITERATION, not once per request.
 * Ask about the weather and the log shows two BEFORE/AFTER pairs — one for
 * the turn where the model asked for the tool, one for the turn where it
 * answered. An earlier version of this comment claimed order 0 made it run
 * first; that was simply wrong.
 *
 * That turns out to be the useful position: inside the loop an advisor sees
 * every iteration AND can change it. LangChain4j can OBSERVE iterations too —
 * AiServices.registerListener(...) with an AiServiceRequestIssuedEvent
 * listener (fires once per model call, i.e. once per lap) and a
 * ToolExecutedEvent listener (once per tool execution), both @Experimental
 * in 1.x — but those listeners only watch; they cannot rewrite the request.
 * The real difference is observe vs modify, not visible vs invisible. (An
 * earlier version of this comment claimed LangChain4j had no per-iteration
 * seam at all; that was wrong as well.)
 *
 * To see only the original request instead, give it an order BELOW
 * ToolCallingAdvisor's (for example HIGHEST_PRECEDENCE + 100). Then it runs
 * once, before the loop starts — and before memory has been appended.
 *
 * ── WHY IT IS CALLED INSPECTION AND NOT APPROVAL ───────────────────────────
 *
 * It used to be ApprovalAdvisor, with the approval logic left as two TODOs.
 * The name promised a human-in-the-loop gate that was never written, which is
 * the same kind of mismatch this project collects elsewhere: something
 * announcing a capability it does not have.
 *
 * The approval flow is real, just not here — it lives in mcp-server and both
 * patterns modules, where there are destructive tools to guard
 * (common/approval/HumanApprovalService). This module has three read-only demo
 * functions and nothing worth gating.
 */
@Slf4j
public class InspectionAdvisor implements BaseAdvisor {

    @Override
    public String getName() {
        return "InspectionAdvisor";
    }

    /**
     * Larger than ToolCallingAdvisor's order, therefore INSIDE the loop.
     * See the class comment — this is deliberate, not a leftover default.
     */
    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientRequest before(
            ChatClientRequest request,
            AdvisorChain advisorChain) {

        log.info("[Advisor] BEFORE — {} messages, user=\"{}\"",
                request.prompt().getInstructions().size(),
                request.prompt().getUserMessage().getText());

        // Returning the request unchanged. This is the seam where an advisor
        // would rewrite the prompt, inject context, or refuse to continue.
        return request;
    }

    @Override
    public ChatClientResponse after(
            ChatClientResponse response,
            AdvisorChain advisorChain) {

        var result = response.chatResponse().getResult();
        String text = result.getOutput().getText();
        boolean asksForTool = result.getOutput().hasToolCalls();

        // The message count and the tool-call flag are what make the iterations
        // legible: on the first lap the model asks for a tool and text is empty,
        // on the last one it answers and asks for nothing. Log both and the
        // loop becomes visible from inside application code.
        log.info("[Advisor] AFTER — toolCalls={}, responseLength={}",
                asksForTool, text != null ? text.length() : 0);

        return response;
    }
}
