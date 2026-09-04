package com.kama.mindagent.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRunMetricsTest {

    @Test
    void recordsPromptCompletionAndTotalTokensAcrossModelCalls() {
        AgentRunMetrics metrics = new AgentRunMetrics();

        metrics.recordModelUsage(new DefaultUsage(12, 5, 17));
        metrics.recordModelUsage(new Usage() {
            @Override
            public Integer getPromptTokens() {
                return 8;
            }

            @Override
            public Integer getCompletionTokens() {
                return 3;
            }

            @Override
            public Integer getTotalTokens() {
                return null;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        });

        assertThat(metrics.usageReports()).isEqualTo(2);
        assertThat(metrics.promptTokens()).isEqualTo(20);
        assertThat(metrics.completionTokens()).isEqualTo(8);
        assertThat(metrics.totalTokens()).isEqualTo(28);
        assertThat(metrics.snapshot().totalTokens()).isEqualTo(28);
    }

    @Test
    void ignoresMissingOrInvalidUsageWithoutCorruptingCounters() {
        AgentRunMetrics metrics = new AgentRunMetrics();

        metrics.recordModelUsage(null);
        metrics.recordModelUsage(new DefaultUsage(-1, 4, -1));

        assertThat(metrics.usageReports()).isEqualTo(1);
        assertThat(metrics.promptTokens()).isZero();
        assertThat(metrics.completionTokens()).isEqualTo(4);
        assertThat(metrics.totalTokens()).isEqualTo(4);
    }
}
