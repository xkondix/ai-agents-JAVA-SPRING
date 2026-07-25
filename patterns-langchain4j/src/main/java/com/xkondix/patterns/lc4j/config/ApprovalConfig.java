package com.xkondix.patterns.lc4j.config;

import com.xkondix.common.approval.ApprovalEndpoints;
import com.xkondix.common.approval.HumanApprovalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the shared human-in-the-loop gate from `common` into this module.
 *
 * The beans are declared EXPLICITLY because com.xkondix.common is outside
 * this application's component-scan package — and that is exactly what we
 * want: modules that must not expose /approvals (mcp-server has its own)
 * simply never declare them.
 *
 * Requires spring.threads.virtual.enabled=true (set in application.yml):
 * an approved-or-not tool call blocks its request thread for as long as the
 * human takes to decide.
 */
@Configuration
public class ApprovalConfig {

    @Bean
    HumanApprovalService humanApprovalService(
            @Value("${xkondix.approval.timeout-minutes:10}") long timeoutMinutes) {
        return new HumanApprovalService(timeoutMinutes, "http://localhost:3000/approvals");
    }

    @Bean
    ApprovalEndpoints approvalEndpoints(HumanApprovalService approvalService) {
        return new ApprovalEndpoints(approvalService);
    }
}
