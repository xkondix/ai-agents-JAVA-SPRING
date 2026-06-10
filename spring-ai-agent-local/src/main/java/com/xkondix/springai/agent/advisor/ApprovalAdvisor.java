package com.xkondix.springai.agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

/**
 * Custom Advisor demonstrating the Spring AI Advisor Pattern.
 *
 * Advisors are interceptors that wrap each step of the ChatClient pipeline.
 * This is Spring AI's equivalent of servlet filters — applied in order,
 * each advisor can inspect and modify the request before it reaches the LLM
 * and the response before it reaches the caller.
 *
 * This advisor:
 *   - before(): logs the user message before sending to LLM
 *   - after():  logs response length; here you would add Approval Flow logic
 *               to block sensitive tool calls and wait for human confirmation
 *
 * Advisor chain order (lower = runs first):
 *   0 - ApprovalAdvisor      (this)
 *   1 - MessageChatMemoryAdvisor
 *   2 - SimpleLoggerAdvisor
 *
 * TODO: implement actual Approval Flow
 *   - inspect tool calls in the response
 *   - for "write_*" or "delete_*" tools — block and wait for human approval
 *   - resume or cancel based on the decision
 */
@Slf4j
public class ApprovalAdvisor implements BaseAdvisor {

    @Override
    public String getName() {
        return "ApprovalAdvisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientRequest before(
            ChatClientRequest request,
            AdvisorChain advisorChain) {

        log.info("[Advisor] BEFORE — user=\"{}\"",
                request.prompt().getUserMessage().getText());

        return request;
    }

    @Override
    public ChatClientResponse after(
            ChatClientResponse response,
            AdvisorChain advisorChain) {

        String text = response.chatResponse()
                .getResult()
                .getOutput()
                .getText();

        log.info("[Advisor] AFTER — response length={}",
                text != null ? text.length() : 0);

        // TODO: inspect tool calls for "write_*" or "delete_*" patterns
        // and implement actual human Approval Flow

        return response;
    }
}
