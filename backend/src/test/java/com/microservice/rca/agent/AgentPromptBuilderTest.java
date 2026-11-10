package com.microservice.rca.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.microservice.rca.tools.DiagnosticToolRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentPromptBuilderTest {

    private final AgentPromptBuilder builder = new AgentPromptBuilder(AgentTestSupport.json());

    @Test
    void builtInPromptDefinesLocalizationAndConvergenceRules() {
        String prompt = builder.systemPrompt();

        assertThat(prompt)
                .contains("调用环节")
                .contains("内部原因")
                .contains("内部原因尚未确认")
                .contains("不得猜测具体代码缺陷")
                .contains("不要求证明")
                .contains("不要仅为了把 MEDIUM 提升到 HIGH 而继续取证")
                .contains("仍可按证据强度使用 HIGH")
                .contains("finalRound=true")
                .doesNotContain("DELAY")
                .doesNotContain("HTTP_500");
    }

    @Test
    void finalRoundAddsRequiredTerminalAction() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry();

        String prompt = builder.userPrompt(
                AgentTestSupport.input(registry, 2, 2), List.of(), null, 2, 0);

        assertThat(prompt)
                .contains("\"finalRound\":true")
                .contains("\"remainingModelCalls\":0")
                .contains("\"requiredAction\":\"FINAL_REPORT_OR_INSUFFICIENT_EVIDENCE\"");
    }

    @Test
    void nonFinalRoundDoesNotRequireTerminalAction() {
        DiagnosticToolRegistry registry = AgentTestSupport.registry();

        String prompt = builder.userPrompt(
                AgentTestSupport.input(registry, 2, 2), List.of(), null, 1, 0);

        assertThat(prompt)
                .contains("\"finalRound\":false")
                .doesNotContain("\"requiredAction\"");
    }
}
