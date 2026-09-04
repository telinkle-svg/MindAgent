package com.kama.mindagent.agent;

import org.springframework.ai.chat.metadata.Usage;

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
    private int usageReports;
    private long promptTokens;
    private long completionTokens;
    private long totalTokens;

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

    /**
     * Records provider-reported token usage for one model response.
     *
     * <p>Providers are allowed to omit usage or report only prompt and
     * completion counts. Missing and negative values are treated as zero;
     * when total tokens are unavailable, the two component counts are used
     * as the fallback total.</p>
     */
    public synchronized void recordModelUsage(Usage usage) {
        if (usage == null) {
            return;
        }
        usageReports++;
        long prompt = nonNegative(usage.getPromptTokens());
        long completion = nonNegative(usage.getCompletionTokens());
        long total = nonNegative(usage.getTotalTokens());
        if (total == 0 && (prompt > 0 || completion > 0)) {
            total = prompt + completion;
        }
        promptTokens += prompt;
        completionTokens += completion;
        totalTokens += total;
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

    public synchronized int usageReports() {
        return usageReports;
    }

    public synchronized long promptTokens() {
        return promptTokens;
    }

    public synchronized long completionTokens() {
        return completionTokens;
    }

    public synchronized long totalTokens() {
        return totalTokens;
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
                terminalReason,
                usageReports,
                promptTokens,
                completionTokens,
                totalTokens
        );
    }

    private long nonNegative(Integer value) {
        return value == null || value < 0 ? 0 : value;
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
            String terminalReason,
            int usageReports,
            long promptTokens,
            long completionTokens,
            long totalTokens
    ) {
    }
}
