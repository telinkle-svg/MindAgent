package com.kama.mindagent.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable safety limits for one Agent run.
 *
 * <p>The policy is deliberately independent from Spring so that the runtime
 * can be exercised deterministically in unit tests and every run receives its
 * own limits.</p>
 */
public final class AgentLoopPolicy {

    public static final int DEFAULT_MAX_ITERATIONS = 20;
    public static final int DEFAULT_MAX_MODEL_CALLS = 20;
    public static final int DEFAULT_MAX_PLAN_REVISIONS = 3;
    public static final int DEFAULT_MAX_TOOL_CALLS = 40;
    public static final Duration DEFAULT_MAX_RUN_DURATION = Duration.ofMinutes(2);

    private final int maxIterations;
    private final int maxModelCalls;
    private final int maxPlanRevisions;
    private final int maxToolCalls;
    private final Duration maxRunDuration;

    public AgentLoopPolicy() {
        this(
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_MAX_MODEL_CALLS,
                DEFAULT_MAX_PLAN_REVISIONS,
                DEFAULT_MAX_TOOL_CALLS,
                DEFAULT_MAX_RUN_DURATION
        );
    }

    public AgentLoopPolicy(
            int maxIterations,
            int maxModelCalls,
            int maxPlanRevisions,
            int maxToolCalls,
            Duration maxRunDuration
    ) {
        this.maxIterations = positive(maxIterations, "maxIterations");
        this.maxModelCalls = positive(maxModelCalls, "maxModelCalls");
        this.maxPlanRevisions = positive(maxPlanRevisions, "maxPlanRevisions");
        this.maxToolCalls = positive(maxToolCalls, "maxToolCalls");
        this.maxRunDuration = requirePositive(maxRunDuration, "maxRunDuration");
    }

    public static AgentLoopPolicy defaults() {
        return new AgentLoopPolicy();
    }

    public int maxIterations() {
        return maxIterations;
    }

    public int maxModelCalls() {
        return maxModelCalls;
    }

    public int maxPlanRevisions() {
        return maxPlanRevisions;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public Duration maxRunDuration() {
        return maxRunDuration;
    }

    public void ensureIterationAllowed(int nextIteration) {
        if (nextIteration > maxIterations) {
            throw new AgentExecutionException(AgentFailureCode.MAX_STEPS_EXCEEDED);
        }
    }

    public void ensureModelCallAllowed(int nextModelCall) {
        if (nextModelCall > maxModelCalls) {
            throw new AgentExecutionException(AgentFailureCode.MAX_MODEL_CALLS_EXCEEDED);
        }
    }

    public void ensurePlanRevisionAllowed(int nextRevision) {
        if (nextRevision > maxPlanRevisions) {
            throw new AgentExecutionException(AgentFailureCode.MAX_PLAN_REVISIONS_EXCEEDED);
        }
    }

    public void ensureToolCallsAllowed(int nextToolCalls) {
        if (nextToolCalls > maxToolCalls) {
            throw new AgentExecutionException(AgentFailureCode.MAX_TOOL_CALLS_EXCEEDED);
        }
    }

    public void ensureDuration(Instant startedAt) {
        Objects.requireNonNull(startedAt, "startedAt cannot be null");
        if (Duration.between(startedAt, Instant.now()).compareTo(maxRunDuration) > 0) {
            throw new AgentExecutionException(AgentFailureCode.MAX_RUN_DURATION_EXCEEDED);
        }
    }

    private static int positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }
}
