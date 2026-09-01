package com.kama.mindagent.agent;

import java.time.Duration;
import java.time.Instant;

/**
 * Lightweight per-run counters used for safety diagnostics and deterministic
 * performance assertions. Mutations are local to one runtime instance.
 */
public final class AgentRunMetrics {

    private final Instant startedAt;
    private int iterations;
    private int modelCalls;
    private int planCalls;
    private int planRevisions;
    private int toolCalls;
    private String terminalReason;

    public AgentRunMetrics() {
        this(Instant.now());
    }

    AgentRunMetrics(Instant startedAt) {
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    public synchronized void recordIteration() {
        iterations++;
    }

    public synchronized void recordModelCall() {
        modelCalls++;
    }

    public synchronized void recordPlanCall() {
        planCalls++;
    }

    public synchronized void recordPlanRevision() {
        planRevisions++;
    }

    public synchronized void recordToolCalls(int count) {
        if (count > 0) {
            toolCalls += count;
        }
    }

    public synchronized void markTerminal(String reason) {
        terminalReason = reason;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public synchronized int iterations() {
        return iterations;
    }

    public synchronized int modelCalls() {
        return modelCalls;
    }

    public synchronized int planCalls() {
        return planCalls;
    }

    public synchronized int planRevisions() {
        return planRevisions;
    }

    public synchronized int toolCalls() {
        return toolCalls;
    }

    public Duration elapsed() {
        return Duration.between(startedAt, Instant.now());
    }

    public synchronized String terminalReason() {
        return terminalReason;
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(
                startedAt,
                iterations,
                modelCalls,
                planCalls,
                planRevisions,
                toolCalls,
                elapsed(),
                terminalReason
        );
    }

    public record Snapshot(
            Instant startedAt,
            int iterations,
            int modelCalls,
            int planCalls,
            int planRevisions,
            int toolCalls,
            Duration elapsed,
            String terminalReason
    ) {
    }
}
