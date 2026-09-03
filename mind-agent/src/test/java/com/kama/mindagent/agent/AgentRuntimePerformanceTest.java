package com.kama.mindagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.agent.context.ContextBudgetPolicy;
import com.kama.mindagent.agent.context.ConversationSummary;
import com.kama.mindagent.agent.planning.PlanningMode;
import com.kama.mindagent.agent.support.AgentTestMessages;
import com.kama.mindagent.agent.support.InMemoryChatMessageFacadeService;
import com.kama.mindagent.agent.support.RecordingAgentEventStream;
import com.kama.mindagent.agent.support.ScriptedModelResponseGateway;
import com.kama.mindagent.converter.ChatMessageConverter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRuntimePerformanceTest {

    private static final int SIMPLE_RUNS = 100;
    private static final int MULTI_STEP_RUNS = 20;
    private static final int SUMMARY_RUNS = 20;
    private static final int CONCURRENT_RUNS = 20;
    private static final Duration MAX_RUN_DURATION = Duration.ofSeconds(5);
    private static final int PERFORMANCE_SCHEMA_VERSION = 1;

    @Test
    @Timeout(value = 60)
    void simpleRunsProduceDeterministicBaseline() {
        IntStream.range(0, 5).forEach(index -> runSimple(index));
        ScenarioResult result = measure("simple", SIMPLE_RUNS, this::runSimple);

        assertCompleted(result, SIMPLE_RUNS);
        assertThat(result.samples()).allSatisfy(sample -> {
            assertThat(sample.gatewayCalls()).isEqualTo(1);
            assertThat(sample.persistedMessages()).isEqualTo(1);
            assertThat(sample.metrics().iterations()).isEqualTo(1);
            assertThat(sample.metrics().modelCalls()).isEqualTo(1);
            assertThat(sample.metrics().contextAssemblies()).isEqualTo(1);
            assertThat(sample.metrics().summaryAttempts()).isZero();
        });
        assertWithinSafetyBound(result);
        writeBaseline(result);
    }

    @Test
    @Timeout(value = 60)
    void multiStepRunsMeasureToolAndContextBudgetOverhead() {
        IntStream.range(0, 5).forEach(index -> runMultiStep(index));
        ScenarioResult result = measure("multi-step", MULTI_STEP_RUNS, this::runMultiStep);

        assertCompleted(result, MULTI_STEP_RUNS);
        assertThat(result.samples()).allSatisfy(sample -> {
            assertThat(sample.gatewayCalls()).isEqualTo(2);
            assertThat(sample.persistedMessages()).isEqualTo(3);
            assertThat(sample.metrics().iterations()).isEqualTo(2);
            assertThat(sample.metrics().modelCalls()).isEqualTo(2);
            assertThat(sample.metrics().toolCalls()).isEqualTo(1);
            assertThat(sample.metrics().truncatedToolResults()).isGreaterThanOrEqualTo(1);
            assertThat(sample.metrics().summaryAttempts()).isZero();
        });
        assertWithinSafetyBound(result);
        writeBaseline(result);
    }

    @Test
    @Timeout(value = 60)
    void longContextRunsMeasureIncrementalSummaryOverhead() {
        IntStream.range(0, 5).forEach(index -> runSummary(index));
        ScenarioResult result = measure("summary", SUMMARY_RUNS, this::runSummary);

        assertCompleted(result, SUMMARY_RUNS);
        assertThat(result.samples()).allSatisfy(sample -> {
            assertThat(sample.gatewayCalls()).isEqualTo(2);
            assertThat(sample.persistedMessages()).isEqualTo(1);
            assertThat(sample.metrics().iterations()).isEqualTo(1);
            assertThat(sample.metrics().modelCalls()).isEqualTo(2);
            assertThat(sample.metrics().summaryAttempts()).isEqualTo(1);
            assertThat(sample.metrics().summaryFailures()).isZero();
            assertThat(sample.metrics().maxOmittedTurns()).isGreaterThan(0);
        });
        assertWithinSafetyBound(result);
        writeBaseline(result);
    }

    @Test
    @Timeout(value = 60)
    void concurrentRunsKeepIndependentSessionState() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(8, CONCURRENT_RUNS));
        List<Future<Sample>> futures = new ArrayList<>(CONCURRENT_RUNS);
        try {
            for (int index = 0; index < CONCURRENT_RUNS; index++) {
                int runIndex = index;
                futures.add(executor.submit(() -> measureOne(runIndex, this::runSimple)));
            }
            List<Sample> samples = new ArrayList<>(CONCURRENT_RUNS);
            for (Future<Sample> future : futures) {
                samples.add(getSample(future));
            }
            ScenarioResult result = new ScenarioResult("concurrent", samples);

            assertCompleted(result, CONCURRENT_RUNS);
            assertThat(samples).extracting(Sample::sessionId).doesNotHaveDuplicates();
            assertThat(samples).allSatisfy(sample -> {
                assertThat(sample.gatewayCalls()).isEqualTo(1);
                assertThat(sample.persistedMessages()).isEqualTo(1);
                assertThat(sample.metrics().iterations()).isEqualTo(1);
                assertThat(sample.metrics().modelCalls()).isEqualTo(1);
                assertThat(sample.metrics().summaryAttempts()).isZero();
            });
            assertWithinSafetyBound(result);
            writeBaseline(result);
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    private Sample getSample(Future<Sample> future)
            throws InterruptedException, ExecutionException, TimeoutException {
        return future.get(30, TimeUnit.SECONDS);
    }

    private ScenarioResult measure(String name, int count, IntFunction<RunResult> runner) {
        List<Sample> samples = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            samples.add(measureOne(index, runner));
        }
        return new ScenarioResult(name, samples);
    }

    private Sample measureOne(int index, IntFunction<RunResult> runner) {
        long started = System.nanoTime();
        RunResult run = runner.apply(index);
        long elapsed = System.nanoTime() - started;
        return new Sample(
                run.sessionId(),
                elapsed,
                run.runtime().metrics().snapshot(),
                run.gateway().calls().size(),
                run.messages().messages().size()
        );
    }

    private RunResult runSimple(int index) {
        String sessionId = "perf-simple-" + index + "-" + Thread.currentThread().getId();
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantText("完成")
        ));
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        AgentRuntime runtime = createRuntime(
                sessionId,
                gateway,
                messages,
                ToolCallingManager.builder().build(),
                List.of(),
                100,
                ContextBudgetPolicy.defaults()
        );
        runtime.execute();
        return new RunResult(sessionId, runtime, gateway, messages);
    }

    private RunResult runMultiStep(int index) {
        String sessionId = "perf-multi-" + index;
        String largeResult = "row-" + "x".repeat(2_000);
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantToolCall(
                        "call-" + index,
                        "databaseQuery",
                        "{\"sql\":\"SELECT 1\"}"
                ),
                AgentTestMessages.assistantText("工具结果已处理")
        ));
        ToolCallingManager toolCallingManager = mock(ToolCallingManager.class);
        ToolExecutionResult executionResult = mock(ToolExecutionResult.class);
        when(executionResult.conversationHistory()).thenReturn(List.of(
                AgentTestMessages.toolResponse("call-" + index, "databaseQuery", largeResult)
        ));
        when(toolCallingManager.executeToolCalls(any(Prompt.class), any(ChatResponse.class)))
                .thenReturn(executionResult);
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        AgentRuntime runtime = createRuntime(
                sessionId,
                gateway,
                messages,
                toolCallingManager,
                List.of(),
                100,
                new ContextBudgetPolicy(256, 10)
        );
        runtime.execute();
        assertThat(messages.messages().get(1).getContent()).isEqualTo(largeResult);
        return new RunResult(sessionId, runtime, gateway, messages);
    }

    private RunResult runSummary(int index) {
        String sessionId = "perf-summary-" + index;
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantText("历史事实摘要"),
                AgentTestMessages.assistantText("完成")
        ));
        List<Message> history = new ArrayList<>();
        for (int turn = 1; turn <= 15; turn++) {
            history.add(new org.springframework.ai.chat.messages.UserMessage("问题-" + turn));
            history.add(AssistantMessage.builder()
                    .content("回答-" + turn)
                    .toolCalls(List.of())
                    .build());
        }
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        AtomicReference<ConversationSummary> summary = new AtomicReference<>();
        AgentRuntime runtime = new AgentRuntime(
                "perf-agent",
                "Performance Agent",
                "deterministic performance fixture",
                "system",
                gateway,
                100,
                history,
                List.of(),
                List.of(),
                sessionId,
                new RecordingAgentEventStream(),
                messages,
                new ChatMessageConverter(new ObjectMapper()),
                ToolCallingManager.builder().build(),
                PlanningMode.AUTO,
                null,
                AgentLoopPolicy.defaults(),
                new ContextBudgetPolicy(512, 2),
                null,
                summary::set,
                "trigger-" + index
        );
        runtime.execute();
        assertThat(summary).hasValueSatisfying(value ->
                assertThat(value.lastSummarizedMessageId()).isEqualTo("trigger-" + index));
        return new RunResult(sessionId, runtime, gateway, messages);
    }

    private AgentRuntime createRuntime(
            String sessionId,
            ModelResponseGateway gateway,
            InMemoryChatMessageFacadeService messages,
            ToolCallingManager toolCallingManager,
            List<Message> history,
            int maxMessages,
            ContextBudgetPolicy contextBudgetPolicy
    ) {
        return new AgentRuntime(
                "perf-agent",
                "Performance Agent",
                "deterministic performance fixture",
                "system",
                gateway,
                maxMessages,
                history,
                List.of(),
                List.of(),
                sessionId,
                new RecordingAgentEventStream(),
                messages,
                new ChatMessageConverter(new ObjectMapper()),
                toolCallingManager,
                PlanningMode.AUTO,
                null,
                AgentLoopPolicy.defaults(),
                contextBudgetPolicy
        );
    }

    private void assertCompleted(ScenarioResult result, int expectedCount) {
        assertThat(result.samples()).hasSize(expectedCount);
        assertThat(result.samples()).allSatisfy(sample -> {
            assertThat(sample.metrics().terminalReason()).isEqualTo("completed");
            assertThat(sample.metrics().summaryFailures()).isZero();
        });
    }

    private void assertWithinSafetyBound(ScenarioResult result) {
        assertThat(result.maxElapsedNanos())
                .as("scenario %s max duration", result.name())
                .isLessThan(MAX_RUN_DURATION.toNanos());
    }

    private void writeBaseline(ScenarioResult result) {
        long p50 = percentile(result.samples(), 0.50);
        long p95 = percentile(result.samples(), 0.95);
        long p99 = percentile(result.samples(), 0.99);
        long totalContextChars = result.samples().stream()
                .mapToLong(sample -> sample.metrics().totalContextChars())
                .sum();
        int maxContextChars = result.samples().stream()
                .mapToInt(sample -> sample.metrics().maxContextChars())
                .max()
                .orElse(0);
        int totalTruncated = result.samples().stream()
                .mapToInt(sample -> sample.metrics().truncatedToolResults())
                .sum();
        int totalSummaryAttempts = result.samples().stream()
                .mapToInt(sample -> sample.metrics().summaryAttempts())
                .sum();
        String report = String.format(Locale.ROOT,
                "scenario=%s%n" +
                        "runs=%d%n" +
                        "p50_ms=%.3f%n" +
                        "p95_ms=%.3f%n" +
                        "p99_ms=%.3f%n" +
                        "max_ms=%.3f%n" +
                        "total_context_chars=%d%n" +
                        "max_context_chars=%d%n" +
                        "total_truncated_tool_results=%d%n" +
                        "total_summary_attempts=%d%n",
                result.name(),
                result.samples().size(),
                nanosToMillis(p50),
                nanosToMillis(p95),
                nanosToMillis(p99),
                nanosToMillis(result.maxElapsedNanos()),
                totalContextChars,
                maxContextChars,
                totalTruncated,
                totalSummaryAttempts
        );
        Path file = Path.of("target", "performance-baseline-" + result.name() + ".txt");
        Path jsonFile = Path.of("target", "performance-baseline-" + result.name() + ".json");
        PerformanceReport jsonReport = PerformanceReport.from(
                result,
                p50,
                p95,
                p99,
                totalContextChars,
                maxContextChars,
                totalTruncated,
                totalSummaryAttempts
        );
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(
                    file,
                    report,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            Files.writeString(
                    jsonFile,
                    new ObjectMapper().writeValueAsString(jsonReport) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            System.out.print("PERF_BASELINE " + report.replace(System.lineSeparator(), " "));
        } catch (IOException exception) {
            throw new UncheckedIOException("Unable to write performance baseline", exception);
        }
    }

    private long percentile(List<Sample> samples, double percentile) {
        List<Long> sorted = samples.stream()
                .map(Sample::elapsedNanos)
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = Math.min(
                sorted.size() - 1,
                Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1)
        );
        return sorted.get(index);
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private record RunResult(
            String sessionId,
            AgentRuntime runtime,
            ScriptedModelResponseGateway gateway,
            InMemoryChatMessageFacadeService messages
    ) {
    }

    private record Sample(
            String sessionId,
            long elapsedNanos,
            AgentRunMetrics.Snapshot metrics,
            int gatewayCalls,
            int persistedMessages
    ) {
    }

    private record ScenarioResult(String name, List<Sample> samples) {
        private long maxElapsedNanos() {
            return samples.stream()
                    .mapToLong(Sample::elapsedNanos)
                    .max()
                    .orElse(0L);
        }
    }

    /** Fixed-shape, machine-readable companion to the human-readable TXT baseline. */
    private record PerformanceReport(
            int schemaVersion,
            String scenario,
            int runs,
            double p50Ms,
            double p95Ms,
            double p99Ms,
            double maxMs,
            long totalContextChars,
            int maxContextChars,
            int totalTruncatedToolResults,
            int totalSummaryAttempts,
            int totalSummaryFailures,
            int terminalSuccesses,
            int terminalFailures,
            java.util.Map<String, Integer> terminalReasons
    ) {
        private static PerformanceReport from(
                ScenarioResult result,
                long p50,
                long p95,
                long p99,
                long totalContextChars,
                int maxContextChars,
                int totalTruncated,
                int totalSummaryAttempts
        ) {
            java.util.Map<String, Integer> terminalReasons = new java.util.TreeMap<>();
            int terminalSuccesses = 0;
            int terminalFailures = 0;
            int totalSummaryFailures = 0;
            for (Sample sample : result.samples()) {
                String reason = sample.metrics().terminalReason();
                String normalizedReason = reason == null || reason.isBlank() ? "unknown" : reason;
                terminalReasons.merge(normalizedReason, 1, Integer::sum);
                if ("completed".equals(normalizedReason)) {
                    terminalSuccesses++;
                } else {
                    terminalFailures++;
                }
                totalSummaryFailures += sample.metrics().summaryFailures();
            }
            return new PerformanceReport(
                    PERFORMANCE_SCHEMA_VERSION,
                    result.name(),
                    result.samples().size(),
                    nanosToMillis(p50),
                    nanosToMillis(p95),
                    nanosToMillis(p99),
                    nanosToMillis(result.maxElapsedNanos()),
                    totalContextChars,
                    maxContextChars,
                    totalTruncated,
                    totalSummaryAttempts,
                    totalSummaryFailures,
                    terminalSuccesses,
                    terminalFailures,
                    terminalReasons
            );
        }

        private static double nanosToMillis(long nanos) {
            return nanos / 1_000_000.0;
        }
    }
}
