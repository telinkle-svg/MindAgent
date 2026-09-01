package com.kama.mindagent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.mindagent.agent.context.ContextBudgetPolicy;
import com.kama.mindagent.agent.context.ConversationSummary;
import com.kama.mindagent.agent.planning.PlanningMode;
import com.kama.mindagent.agent.support.InMemoryChatMessageFacadeService;
import com.kama.mindagent.agent.support.RecordingAgentEventStream;
import com.kama.mindagent.agent.support.ScriptedModelResponseGateway;
import com.kama.mindagent.agent.support.AgentTestMessages;
import com.kama.mindagent.converter.ChatMessageConverter;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.model.tool.ToolCallingManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
class AgentRuntimeSummaryIntegrationTest {

    @Test
    void summarizesOmittedTurnsOnceAndUsesThePersistedSummaryForTheRun() {
        ScriptedModelResponseGateway gateway = new ScriptedModelResponseGateway(List.of(
                AgentTestMessages.assistantText("earlier turns summary"),
                AgentTestMessages.assistantText("最终回答")
        ));
        InMemoryChatMessageFacadeService messages = new InMemoryChatMessageFacadeService();
        AtomicReference<ConversationSummary> persistedSummary = new AtomicReference<>();
        List<Message> history = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            history.add(new UserMessage("问题-" + i));
            history.add(AssistantMessage.builder()
                    .content("回答-" + i)
                    .toolCalls(List.of())
                    .build());
        }

        AgentRuntime runtime = new AgentRuntime(
                "test-agent",
                "Test Agent",
                "summary test",
                "system",
                gateway,
                100,
                history,
                List.of(),
                List.of(),
                "session",
                new RecordingAgentEventStream(),
                messages,
                new ChatMessageConverter(new ObjectMapper()),
                ToolCallingManager.builder().build(),
                PlanningMode.AUTO,
                null,
                AgentLoopPolicy.defaults(),
                new ContextBudgetPolicy(1000, 2),
                null,
                persistedSummary::set,
                "trigger-message"
        );

        runtime.execute();

        assertThat(persistedSummary).hasValueSatisfying(summary -> {
            assertThat(summary.version()).isEqualTo(1);
            assertThat(summary.lastSummarizedMessageId()).isEqualTo("trigger-message");
            assertThat(summary.text()).isEqualTo("earlier turns summary");
        });
        assertThat(gateway.calls()).hasSize(2);
        assertThat(gateway.calls().get(0).tools()).isEmpty();
        assertThat(gateway.calls().get(1).prompt().getInstructions())
                .anySatisfy(message -> assertThat(message.getText())
                        .contains("【会话摘要】")
                        .contains("earlier turns summary"));
        assertThat(runtime.metrics().modelCalls()).isEqualTo(2);
        assertThat(runtime.metrics().iterations()).isEqualTo(1);
        assertThat(runtime.metrics().terminalReason()).isEqualTo("completed");
    }
}
