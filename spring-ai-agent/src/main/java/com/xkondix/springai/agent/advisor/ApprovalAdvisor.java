package com.xkondix.springai.agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;

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

        return response;
    }
}