package com.kama.mindagent.agent;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentLoopPolicyTest {

    @Test
    void defaultsKeepLoopAndCallBudgetsBounded() {
        AgentLoopPolicy policy = AgentLoopPolicy.defaults();

        assertThat(policy.maxIterations()).isEqualTo(20);
        assertThat(policy.maxModelCalls()).isEqualTo(20);
        assertThat(policy.maxPlanRevisions()).isEqualTo(3);
        assertThat(policy.maxToolCalls()).isEqualTo(40);
        assertThat(policy.maxRunDuration()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void rejectsNonPositiveLimits() {
        assertThatThrownBy(() -> new AgentLoopPolicy(0, 20, 3, 40, Duration.ofMinutes(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentLoopPolicy(20, 20, 3, 40, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void raisesTypedFailureWhenIterationBudgetIsExceeded() {
        AgentLoopPolicy policy = new AgentLoopPolicy(2, 20, 3, 40, Duration.ofMinutes(2));

        policy.ensureIterationAllowed(2);

        assertThatThrownBy(() -> policy.ensureIterationAllowed(3))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(exception -> ((AgentExecutionException) exception).getErrorCode())
                .isEqualTo(AgentFailureCode.MAX_STEPS_EXCEEDED);
    }

    @Test
    void raisesTypedFailureWhenPlanRevisionBudgetIsExceeded() {
        AgentLoopPolicy policy = new AgentLoopPolicy(20, 20, 1, 40, Duration.ofMinutes(2));

        policy.ensurePlanRevisionAllowed(1);

        assertThatThrownBy(() -> policy.ensurePlanRevisionAllowed(2))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(exception -> ((AgentExecutionException) exception).getErrorCode())
                .isEqualTo(AgentFailureCode.MAX_PLAN_REVISIONS_EXCEEDED);
    }

    @Test
    void raisesTypedFailureWhenRunDurationIsExceeded() {
        AgentLoopPolicy policy = new AgentLoopPolicy(20, 20, 3, 40, Duration.ofSeconds(1));

        assertThatThrownBy(() -> policy.ensureDuration(Instant.now().minusSeconds(2)))
                .isInstanceOf(AgentExecutionException.class)
                .extracting(exception -> ((AgentExecutionException) exception).getErrorCode())
                .isEqualTo(AgentFailureCode.MAX_RUN_DURATION_EXCEEDED);
    }
}
