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
import com.kama.mindagent.message.AgentEvent;
import com.kama.mindagent.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fault-injection coverage for failures that are safe to recover from inside
 * one run. Provider and tool failures remain fail-fast; summary maintenance
 * failures fall back to the bounded context because summarization is optional.
 */
class AgentRuntimeFaultInjectionTest {

    @Test
    void summaryProviderFailureFallsBackToBoundedContextAndCompletes() {
        AtomicInteger calls = new AtomicInteger();
        ModelResponseGateway gateway = (prompt, systemPrompt, tools) -> {
            if (calls.getAndIncrement() == 0) {
                throw new IllegalStateException("injected summary provider failure");
            }
            return AgentTestMessages.assistantText("最终回答");
        };
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream events = new RecordingAgentEventStream();
        AgentRuntime runtime = createRuntime(gateway, messages, events, summary -> {
        });

        runtime.execute();

        assertThat(calls).hasValue(2);
        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT);
        assertThat(runtime.metrics().summaryAttempts()).isEqualTo(1);
        assertThat(runtime.metrics().summaryFailures()).isEqualTo(1);
        assertThat(runtime.metrics().maxOmittedTurns()).isGreaterThan(0);
        assertThat(runtime.metrics().terminalReason()).isEqualTo("completed");
        assertThat(events.sentEvents()).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.AI_DONE)
                .doesNotContain(AgentEvent.Type.AI_ERROR);
    }

    @Test
    void summaryPersistenceFailureFallsBackWithoutPublishingPartialSummary() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantText("摘要结果"),
                AgentTestMessages.assistantText("最终回答")
        ));
        AtomicInteger persistAttempts = new AtomicInteger();
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        RecordingAgentEventStream events = new RecordingAgentEventStream();
        AgentRuntime runtime = createRuntime(
                gateway,
                messages,
                events,
                summary -> {
                    persistAttempts.incrementAndGet();
                    throw new IllegalStateException("injected summary persistence failure");
                }
        );

        runtime.execute();

        assertThat(gateway.calls()).hasSize(2);
        assertThat(persistAttempts).hasValue(1);
        assertThat(messages.messages()).extracting(ChatMessageDTO::getRole)
                .containsExactly(ChatMessageDTO.RoleType.ASSISTANT);
        assertThat(runtime.metrics().summaryAttempts()).isEqualTo(1);
        assertThat(runtime.metrics().summaryFailures()).isEqualTo(1);
        assertThat(runtime.metrics().terminalReason()).isEqualTo("completed");
        assertThat(events.sentEvents()).extracting(AgentEvent::getType)
                .contains(AgentEvent.Type.AI_DONE)
                .doesNotContain(AgentEvent.Type.AI_ERROR);
        assertThat(gateway.calls().get(1).prompt().getInstructions())
                .noneMatch(message -> message.getText().contains("【会话摘要】"));
    }

    private AgentRuntime createRuntime(
            ModelResponseGateway gateway,
            InMemoryChatMessageFacadeService messages,
            RecordingAgentEventStream events,
            java.util.function.Consumer<ConversationSummary> summaryPersister
    ) {
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add(new UserMessage("问题-" + i));
            history.add(AssistantMessage.builder()
                    .content("回答-" + i)
                    .toolCalls(List.of())
                    .build());
        }
        return new AgentRuntime(
                "test-agent",
                "Test Agent",
                "fault injection test",
                "system",
                gateway,
                100,
                history,
                List.of(),
                List.of(),
                "fault-session",
                events,
                messages,
                new ChatMessageConverter(new ObjectMapper()),
                ToolCallingManager.builder().build(),
                PlanningMode.AUTO,
                null,
                AgentLoopPolicy.defaults(),
                new ContextBudgetPolicy(1000, 2),
                null,
                summaryPersister,
                "trigger-message"
        );
    }

}
