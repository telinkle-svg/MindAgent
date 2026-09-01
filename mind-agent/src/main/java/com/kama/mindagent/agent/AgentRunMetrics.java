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
    private int contextAssemblies;
    private long totalContextChars;
    private int maxContextChars;
    private int maxOmittedTurns;
    private int truncatedToolResults;
    private int summaryAttempts;
    private int summaryFailures;

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

    public synchronized void recordContext(
            int contextChars,
            int omittedTurns,
            int truncatedToolResults
    ) {
        contextAssemblies++;
        int boundedChars = Math.max(0, contextChars);
        totalContextChars += boundedChars;
        maxContextChars = Math.max(maxContextChars, boundedChars);
        maxOmittedTurns = Math.max(maxOmittedTurns, Math.max(0, omittedTurns));
        if (truncatedToolResults > 0) {
            this.truncatedToolResults += truncatedToolResults;
        }
    }

    public synchronized void recordToolResultTruncations(int count) {
        if (count > 0) {
            truncatedToolResults += count;
        }
    }

    public synchronized void recordSummaryAttempt() {
        summaryAttempts++;
    }

    public synchronized void recordSummaryFailure() {
        summaryFailures++;
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

    public synchronized int contextAssemblies() {
        return contextAssemblies;
    }

    public synchronized long totalContextChars() {
        return totalContextChars;
    }

    public synchronized int maxContextChars() {
        return maxContextChars;
    }

    public synchronized int maxOmittedTurns() {
        return maxOmittedTurns;
    }

    public synchronized int truncatedToolResults() {
        return truncatedToolResults;
    }

    public synchronized int summaryAttempts() {
        return summaryAttempts;
    }

    public synchronized int summaryFailures() {
        return summaryFailures;
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
                contextAssemblies,
                totalContextChars,
                maxContextChars,
                maxOmittedTurns,
                truncatedToolResults,
                summaryAttempts,
                summaryFailures,
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
            int contextAssemblies,
            long totalContextChars,
            int maxContextChars,
            int maxOmittedTurns,
            int truncatedToolResults,
            int summaryAttempts,
            int summaryFailures,
            Duration elapsed,
            String terminalReason
    ) {
    }
}
